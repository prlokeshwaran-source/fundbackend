package com.example.rkfund.controller;

import java.util.Map;
import com.example.rkfund.service.PartnerService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private final PartnerService service;
    public AdminAuthController(PartnerService service) { this.service = service; }
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        return service.adminLogin(body.get("email"), body.get("password"));
    }
}
