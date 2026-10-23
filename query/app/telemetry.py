"""OpenTelemetry tracing. Off unless OTEL_EXPORTER_OTLP_ENDPOINT is set (e.g. http://jaeger:4318 with the
`observability` compose profile). Every HTTP request, agent node, tool call and SQL-backed step becomes a span,
with token counts, cost and outcome as attributes, so one trace shows the whole answer.

LangSmith tracing of the LangGraph run works independently: set LANGSMITH_TRACING=true and LANGSMITH_API_KEY.
"""
import functools
import logging
import os
from contextlib import contextmanager
from typing import Any, Callable

log = logging.getLogger("docassist.telemetry")

try:
    from opentelemetry import trace as _trace
    _tracer = _trace.get_tracer("docassist.query")
except ImportError:  # tests and minimal installs run without OpenTelemetry
    _trace = None
    _tracer = None


def setup(app: Any) -> None:
    endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT")
    if not endpoint or _trace is None:
        log.info("tracing disabled (set OTEL_EXPORTER_OTLP_ENDPOINT to enable)")
        return
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor

    provider = TracerProvider(resource=Resource.create({"service.name": os.environ.get("OTEL_SERVICE_NAME", "docassist-query")}))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    _trace.set_tracer_provider(provider)
    FastAPIInstrumentor.instrument_app(app, excluded_urls="health")
    log.info("tracing to %s", endpoint)


@contextmanager
def span(name: str, **attributes: Any):
    if _tracer is None:
        yield None
        return
    with _tracer.start_as_current_span(name) as s:
        for k, v in attributes.items():
            if v is not None:
                s.set_attribute(k, v)
        yield s


def set_attributes(s: Any, **attributes: Any) -> None:
    if s is None:
        return
    for k, v in attributes.items():
        if v is not None:
            s.set_attribute(k, v)


def traced_node(name: str) -> Callable:
    """Wraps a LangGraph node in a span named agent.<node>. Keeps the (state, config) signature LangGraph inspects."""
    def decorate(fn: Callable) -> Callable:
        @functools.wraps(fn)
        async def wrapper(state, config):
            with span(f"agent.{name}") as s:
                update = await fn(state, config)
                if s is not None and isinstance(update, dict):
                    usage = (update.get("usage") or [])[-1:] if name == "agent" else []
                    for u in usage:
                        set_attributes(s, **{"llm.model": u.get("model"), "llm.input_tokens": u.get("inputTokens"),
                                             "llm.output_tokens": u.get("outputTokens"),
                                             "llm.cache_read_tokens": u.get("cacheReadTokens")})
                    set_attributes(s, **{"agent.verdict": update.get("verdict"),
                                         "agent.fallback_reason": update.get("fallback_reason")})
                    if isinstance(update.get("result"), dict):
                        r = update["result"]
                        set_attributes(s, **{"answer.outcome": r.get("outcome"), "answer.cost_usd": r.get("costUsd"),
                                             "answer.citations": len(r.get("citations") or [])})
                return update
        return wrapper
    return decorate
