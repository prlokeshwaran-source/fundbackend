package com.example.rkfund.controller;

import java.util.List;
import java.util.Map;

import com.example.rkfund.service.PartnerService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/partner")
public class PartnerController {
    private final PartnerService service;
    public PartnerController(PartnerService service) { this.service = service; }

    @PostMapping("/auth/send-otp")
    public Map<String, Object> sendOtp(@RequestBody Map<String, String> body) { return service.sendOtp(body.get("phone")); }
    @PostMapping("/auth/verify-otp")
    public Map<String, Object> verifyOtp(@RequestBody Map<String, String> body) { return service.verifyOtp(body.get("phone"), body.get("otp")); }
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam String phone) { return service.dashboard(phone); }
    @GetMapping("/customers")
    public List<Map> customers(@RequestParam String phone, @RequestParam(required = false) String status) { return service.customers(phone, status); }
    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map addCustomer(@RequestParam String phone, @RequestBody Map<String, Object> body) { return service.addCustomer(phone, body); }
    @PatchMapping("/customers/{id}")
    public Map updateCustomer(@RequestParam String phone, @PathVariable String id, @RequestBody Map<String, Object> body) { return service.updateCustomer(phone, id, body); }
    @GetMapping("/wallet")
    public Map wallet(@RequestParam String phone) { return service.wallet(phone); }
    @GetMapping("/training")
    public List<Map> training() { return service.training(); }
    @GetMapping("/training/{id}/document")
    public ResponseEntity<Resource> trainingDocument(@PathVariable String id) {
        var document = service.trainingDocument(id);
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        try {
            if (document.getContentType() != null) type = MediaType.parseMediaType(document.getContentType());
        } catch (IllegalArgumentException ignored) { }
        return ResponseEntity.ok().contentType(type)
                .header("Content-Disposition", "inline; filename=\"" + document.getFilename().replace("\"", "") + "\"")
                .body(document);
    }
    @GetMapping("/notifications")
    public List<Map> notifications(@RequestParam String phone) { return service.notifications(phone); }
    @GetMapping("/profile")
    public Map<String, Object> profile(@RequestParam String phone) { return service.profile(phone); }
    @PatchMapping("/profile")
    public Map<String, Object> updateProfile(@RequestParam String phone, @RequestBody Map<String, Object> body) { return service.updateProfile(phone, body); }
    @PatchMapping("/profile/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestParam String phone, @RequestBody Map<String, String> body) {
        service.changePassword(phone, body.get("currentPassword"), body.get("newPassword"));
    }
}
