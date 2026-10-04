package com.example.rkfund.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminModuleService {
    private static final Map<String, String> COLLECTIONS = Map.ofEntries(
            Map.entry("members", "members"), Map.entry("approvals", "members"),
            Map.entry("orders", "orders"), Map.entry("payments", "payments"),
            Map.entry("notifications", "notifications"), Map.entry("followups", "followups"),
            Map.entry("commissions", "commissions"), Map.entry("settings", "settings"),
            Map.entry("reports", "reports"), Map.entry("training", "training"));
    private static final Set<String> READ_ONLY = Set.of("reports");
    private final MongoTemplate mongo;
    private final GridFsTemplate gridFs;
    @Value("${app.admin.email:admin@rkfund.com}") private String adminEmail;

    public AdminModuleService(MongoTemplate mongo, GridFsTemplate gridFs) { this.mongo = mongo; this.gridFs = gridFs; }

    public Map uploadTrainingDocument(String title, String description, MultipartFile file, String videoUrl) {
        boolean hasFile = file != null && !file.isEmpty();
        String link = videoUrl == null ? "" : videoUrl.trim();
        if (title == null || title.isBlank() || (!hasFile && link.isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Training title and a file or video link are required");
        if (!link.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(link);
                if (!Set.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme().toLowerCase())
                        || uri.getHost() == null)
                    throw new IllegalArgumentException("URL must use HTTP or HTTPS");
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid HTTP or HTTPS video link");
            }
        }
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("title", title.trim());
        item.put("description", description == null ? "" : description.trim());
        if (!link.isBlank()) {
            item.put("videoUrl", link);
            item.put("type", "video");
        }
        if (hasFile) {
            try {
                String filename = file.getOriginalFilename() == null ? "training-document" : file.getOriginalFilename();
                ObjectId documentId = gridFs.store(file.getInputStream(), filename,
                        file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                        new org.bson.Document("title", title.trim()));
                item.put("fileName", filename);
                item.put("contentType", file.getContentType() == null ? "application/octet-stream" : file.getContentType());
                item.put("documentId", documentId.toHexString());
                item.put("documentUrl", "/api/partner/training/" + documentId.toHexString() + "/document");
            } catch (java.io.IOException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the training document");
            }
        }
        item.put("createdAt", Instant.now().toString());
        return mongo.save(item, "training");
    }

    public Map<String, Object> adminProfile() {
        Map stored = mongo.findById("admin-profile", Map.class, "settings");
        Map<String, Object> profile = new java.util.LinkedHashMap<>();
        profile.put("id", "admin-profile");
        profile.put("name", stored == null ? "Administrator" : stored.getOrDefault("name", "Administrator"));
        profile.put("email", adminEmail);
        profile.put("role", "ADMIN");
        if (stored != null && stored.get("phone") != null) profile.put("phone", stored.get("phone"));
        if (stored != null && stored.get("updatedAt") != null) profile.put("updatedAt", stored.get("updatedAt"));
        return profile;
    }

    public Map<String, Object> updateAdminProfile(Map<String, Object> changes) {
        Map<String, Object> allowed = new java.util.LinkedHashMap<>();
        if (changes.containsKey("name")) {
            Object name = changes.get("name");
            if (name == null || String.valueOf(name).isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Admin name cannot be blank");
            allowed.put("name", String.valueOf(name).trim());
        }
        if (changes.containsKey("phone")) allowed.put("phone", changes.get("phone"));
        if (allowed.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a name or phone to update");
        allowed.put("_id", "admin-profile");
        allowed.put("updatedAt", Instant.now().toString());
        mongo.save(allowed, "settings");
        return adminProfile();
    }

    public List<Map> adminNotifications() {
        return mongo.findAll(Map.class, "notifications");
    }

    public List<Map> list(String module, String status) {
        String collection = collection(module);
        Query query = new Query();
        if (status != null && !status.isBlank()) query.addCriteria(Criteria.where("status").is(status));
        return mongo.find(query, Map.class, collection);
    }

    public Map create(String module, Map<String, Object> data) {
        String collection = writable(module);
        data.remove("_id");
        String key = module.toLowerCase();
        if ("members".equals(key)) {
            Object name = data.get("name");
            Object memberPhone = data.get("phone");
            if (name == null || String.valueOf(name).isBlank() || memberPhone == null || String.valueOf(memberPhone).isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Member name and phone number are required");
            if (mongo.count(Query.query(Criteria.where("phone").is(memberPhone)), "members") > 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone number already exists");
            data.putIfAbsent("status", "pending");
            ObjectId id = new ObjectId();
            data.put("_id", id);
            data.putIfAbsent("memberId", id.toHexString());
            Object phone = data.get("phone");
            Map existingOrder = phone == null ? null : mongo.findOne(Query.query(Criteria.where("customerPhone").is(phone))
                    .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")),
                    Map.class, "orders");
            data.put("orderId", existingOrder == null ? null : existingOrder.get("orderId"));
        }
        if ("orders".equals(key)) {
            String orderId = "ORD-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-" + new ObjectId().toHexString().substring(0, 6).toUpperCase();
            data.put("orderId", orderId);
            attachMemberId(data);
        }
        if ("payments".equals(key)) {
            attachMemberId(data);
            Object phone = data.get("customerPhone");
            if (phone != null) {
                Map order = mongo.findOne(Query.query(Criteria.where("customerPhone").is(phone))
                        .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")),
                        Map.class, "orders");
                if (order != null && order.get("orderId") != null) data.put("orderId", order.get("orderId"));
            }
        }
        data.putIfAbsent("createdAt", Instant.now().toString());
        data.put("updatedAt", Instant.now().toString());
        return mongo.save(data, collection);
    }

    private void attachMemberId(Map<String, Object> data) {
        Object phone = data.get("customerPhone");
        if (phone == null) return;
        Map member = mongo.findOne(Query.query(Criteria.where("phone").is(phone)), Map.class, "members");
        if (member == null)
            member = mongo.findOne(Query.query(Criteria.where("phone").is(phone)), Map.class, "users");
        if (member == null) return;
        Object memberId = member.get("memberId");
        if (memberId == null) memberId = member.get("_id");
        if (memberId != null) data.put("memberId", String.valueOf(memberId));
    }

    public Map get(String module, String id) {
        return mongo.findById(objectId(id), Map.class, collection(module));
    }

    public Map update(String module, String id, Map<String, Object> data) {
        String collection = writable(module);
        Query query = Query.query(Criteria.where("_id").is(objectId(id)));
        Update update = new Update();
        data.remove("_id");
        data.forEach(update::set);
        update.set("updatedAt", Instant.now().toString());
        if (mongo.updateFirst(query, update, collection).getMatchedCount() == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
        return get(module, id);
    }

    public void delete(String module, String id) {
        mongo.remove(Query.query(Criteria.where("_id").is(objectId(id))), Map.class, writable(module));
    }

    public Map approve(String id, String status, String reason) {
        if (!Set.of("approved", "rejected", "pending").contains(status.toLowerCase()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be approved, rejected, or pending");
        Map<String, Object> changes = new java.util.HashMap<>();
        changes.put("status", status.toLowerCase());
        changes.put("approvalReason", reason);
        return update("members", id, changes);
    }

    public Map<String, Object> dashboard() {
        long members = mongo.count(new Query(), "members");
        long pending = mongo.count(Query.query(Criteria.where("status").is("pending")), "members");
        long orders = mongo.count(new Query(), "orders");
        long payments = mongo.count(new Query(), "payments");
        return Map.of("members", members, "pendingApprovals", pending, "orders", orders,
                "payments", payments, "generatedAt", Instant.now().toString());
    }

    public Map<String, Object> reportSummary() {
        List<Map> paymentRows = mongo.findAll(Map.class, "payments");
        double paid = paymentRows.stream().filter(row -> "paid".equalsIgnoreCase(String.valueOf(row.get("status"))))
                .mapToDouble(row -> amount(row.get("amount"))).sum();
        double pending = paymentRows.stream().filter(row -> "pending".equalsIgnoreCase(String.valueOf(row.get("status"))))
                .mapToDouble(row -> amount(row.get("amount"))).sum();
        return Map.of("members", mongo.count(new Query(), "members"),
                "orders", mongo.count(new Query(), "orders"), "paymentCount", paymentRows.size(),
                "paidAmount", paid, "pendingAmount", pending, "generatedAt", Instant.now().toString());
    }

    private double amount(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ex) { return 0; }
    }

    private String collection(String module) {
        String collection = COLLECTIONS.get(module.toLowerCase());
        if (collection == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown module");
        return collection;
    }
    private String writable(String module) {
        if (READ_ONLY.contains(module.toLowerCase()))
            throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Reports are generated from stored data");
        return collection(module);
    }
    private Object objectId(String id) {
        try { return new ObjectId(id); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid record id"); }
    }
}
