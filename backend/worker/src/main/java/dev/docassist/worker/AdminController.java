package dev.docassist.worker;

import dev.docassist.worker.connector.FilesystemConnector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal admin endpoints (not exposed through the UI proxy). */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final FilesystemConnector connector;

    public AdminController(FilesystemConnector connector) {
        this.connector = connector;
    }

    /** Trigger a connector scan now instead of waiting for the next poll. */
    @PostMapping("/sync")
    public FilesystemConnector.ScanReport sync() {
        return connector.scan();
    }

    @GetMapping("/connector")
    public FilesystemConnector.ScanReport status() {
        return connector.lastReport();
    }
}
