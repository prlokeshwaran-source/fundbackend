package com.example.rkfund.controller;

import java.util.List;
import java.util.Map;

import com.example.rkfund.service.AdminModuleService;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminModuleController {
    private final AdminModuleService service;
    public AdminModuleController(AdminModuleService service) { this.service = service; }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() { return service.dashboard(); }

    @GetMapping("/reports/summary")
    public Map<String, Object> reportSummary() { return service.reportSummary(); }

    /** Returns the admin profile used by the admin profile page. */
    @GetMapping("/profile")
    public Map<String, Object> profile() { return service.adminProfile(); }

    /** Updates editable admin profile details (the login email remains environment-configured). */
    @PatchMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody Map<String, Object> data) {
        return service.updateAdminProfile(data);
    }

    /** App-wide settings for the admin settings page. */
    @GetMapping("/app-settings")
    public List<Map> appSettings() { return service.list("settings", null); }

    /** Notifications available for the admin to receive. */
    @GetMapping("/notifications/received")
    public List<Map> receivedNotifications() { return service.adminNotifications(); }

    @PostMapping(value = "/training/documents", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public Map uploadTrainingDocument(@RequestParam String title,
                                      @RequestParam(required = false) String description,
                                      @RequestParam(required = false) MultipartFile file,
                                      @RequestParam(required = false) String videoUrl) {
        return service.uploadTrainingDocument(title, description, file, videoUrl);
    }

    @GetMapping("/{module}")
    public List<Map> list(@PathVariable String module, @RequestParam(required = false) String status) {
        return service.list(module, status);
    }

    @GetMapping("/{module}/{id}")
    public Map get(@PathVariable String module, @PathVariable String id) { return service.get(module, id); }

    @PostMapping("/{module}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map create(@PathVariable String module, @RequestBody Map<String, Object> data) {
        return service.create(module, data);
    }

    @PutMapping("/{module}/{id}")
    public Map update(@PathVariable String module, @PathVariable String id, @RequestBody Map<String, Object> data) {
        return service.update(module, id, data);
    }

    @DeleteMapping("/{module}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String module, @PathVariable String id) { service.delete(module, id); }

    @PatchMapping("/approvals/{id}")
    public Map approval(@PathVariable String id, @RequestBody Map<String, String> body) {
        return service.approve(id, body.getOrDefault("status", ""), body.get("reason"));
    }
}
