package com.example.rkfund.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.rkfund.entity.User;
import com.example.rkfund.repository.UserRepository;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PartnerService {
    private record Otp(String code, long expiresAt) {}
    private final Map<String, Otp> otpStore = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final MongoTemplate mongo;
    private final GridFsTemplate gridFs;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    @Value("${app.auth.dev-otp:}") private String devOtp;
    @Value("${app.admin.email:admin@rkfund.com}") private String adminEmail;
    @Value("${app.admin.password:ChangeMe123!}") private String adminPassword;

    public PartnerService(MongoTemplate mongo, GridFsTemplate gridFs, UserRepository users, PasswordEncoder encoder) {
        this.mongo = mongo; this.gridFs = gridFs; this.users = users; this.encoder = encoder;
    }

    public Map<String, Object> sendOtp(String phone) {
        requirePhone(phone);
        String code = devOtp.isBlank() ? String.format("%06d", random.nextInt(1_000_000)) : devOtp;
        otpStore.put(phone, new Otp(code, System.currentTimeMillis() + 300_000));
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("message", "OTP generated. Connect an SMS provider to deliver it to the user.");
        response.put("expiresInSeconds", 300);
        if (!devOtp.isBlank()) response.put("developmentOtp", code);
        return response;
    }

    public Map<String, Object> verifyOtp(String phone, String code) {
        requirePhone(phone);
        Otp otp = otpStore.get(phone);
        if (otp == null || otp.expiresAt() < System.currentTimeMillis() || !otp.code().equals(code))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired OTP");
        otpStore.remove(phone);
        User user = mongo.findOne(Query.query(Criteria.where("phone").is(phone)), User.class);
        if (user == null) {
            user = new User(); user.setPhone(phone); user.setName(""); user.setEmail(""); user.setPassword("");
            user = users.save(user);
        }
        return Map.of("user", publicProfile(user), "message", "Phone verified");
    }

    public Map<String, Object> adminLogin(String email, String password) {
        if (email == null || password == null || !adminEmail.equalsIgnoreCase(email) || !adminPassword.equals(password))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin email or password");
        return Map.of("email", adminEmail, "role", "ADMIN", "message", "Admin login successful");
    }

    public Map<String, Object> dashboard(String phone) {
        User user = findPartner(phone);
        List<Map> customerRows = mongo.find(Query.query(Criteria.where("partnerPhone").is(phone)), Map.class, "customers");
        List<Map> commissions = mongo.find(Query.query(Criteria.where("partnerPhone").is(phone)), Map.class, "commissions");
        List<Map> orders = mongo.find(Query.query(Criteria.where("partnerPhone").is(phone)), Map.class, "orders");
        double earnings = commissions.stream().mapToDouble(row -> amount(row.get("amount"))).sum();
        double pending = commissions.stream().filter(row -> "pending".equalsIgnoreCase(String.valueOf(row.get("status"))))
                .mapToDouble(row -> amount(row.get("amount"))).sum();
        long membership = customerRows.stream().filter(row -> "membership".equalsIgnoreCase(String.valueOf(row.get("interestedIn")))).count();
        return Map.of("membershipReferred", membership, "handbookOrders", orders.size(), "totalEarnings", earnings,
                "pendingCommission", pending, "profile", publicProfile(user));
    }

    public List<Map> customers(String phone, String status) {
        findPartner(phone);
        Query query = Query.query(Criteria.where("partnerPhone").is(phone));
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("all")) query.addCriteria(Criteria.where("status").is(status));
        return mongo.find(query, Map.class, "customers");
    }

    public Map addCustomer(String phone, Map<String, Object> data) {
        findPartner(phone);
        if (data.get("name") == null || data.get("phone") == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer name and phone are required");
        data.remove("_id"); data.put("partnerPhone", phone); data.putIfAbsent("status", "new");
        data.put("createdAt", Instant.now().toString()); data.put("updatedAt", Instant.now().toString());
        return mongo.save(data, "customers");
    }

    public Map updateCustomer(String partnerPhone, String id, Map<String, Object> data) {
        findPartner(partnerPhone);
        return updateOwned("customers", id, "partnerPhone", partnerPhone, data);
    }

    public Map wallet(String phone) {
        findPartner(phone);
        List<Map> commissions = mongo.find(Query.query(Criteria.where("partnerPhone").is(phone)), Map.class, "commissions");
        double total = commissions.stream().mapToDouble(row -> amount(row.get("amount"))).sum();
        double pending = commissions.stream().filter(row -> "pending".equalsIgnoreCase(String.valueOf(row.get("status"))))
                .mapToDouble(row -> amount(row.get("amount"))).sum();
        return Map.of("totalEarnings", total, "pendingCommission", pending, "transactions", commissions);
    }

    public List<Map> notifications(String phone) {
        findPartner(phone);
        return mongo.find(Query.query(new Criteria().orOperator(
                Criteria.where("partnerPhone").is(phone), Criteria.where("partnerPhone").exists(false))),
                Map.class, "notifications");
    }

    public List<Map> training() { return mongo.findAll(Map.class, "training"); }

    public GridFsResource trainingDocument(String id) {
        ObjectId objectId;
        try { objectId = new ObjectId(id); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid training document id"); }
        com.mongodb.client.gridfs.model.GridFSFile file = gridFs.findOne(Query.query(Criteria.where("_id").is(objectId)));
        if (file == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Training document not found");
        return gridFs.getResource(file);
    }

    public Map<String, Object> profile(String phone) { return publicProfile(findPartner(phone)); }

    public Map<String, Object> updateProfile(String phone, Map<String, Object> changes) {
        findPartner(phone);
        User user = mongo.findOne(Query.query(Criteria.where("phone").is(phone)), User.class);
        if (user != null) {
            if (changes.containsKey("name")) user.setName(String.valueOf(changes.get("name")));
            if (changes.containsKey("city")) user.setCity(String.valueOf(changes.get("city")));
            if (changes.containsKey("email")) user.setEmail(String.valueOf(changes.get("email")));
            users.save(user);
            return publicProfile(user);
        }
        Query memberQuery = Query.query(Criteria.where("phone").is(phone).and("status").is("approved"));
        Update update = new Update();
        if (changes.containsKey("name")) update.set("name", String.valueOf(changes.get("name")));
        if (changes.containsKey("city")) update.set("city", String.valueOf(changes.get("city")));
        if (changes.containsKey("email")) update.set("email", String.valueOf(changes.get("email")));
        if (update.getUpdateObject().isEmpty()) return profile(phone);
        if (mongo.updateFirst(memberQuery, update, "members").getMatchedCount() == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approved member not found");
        return profile(phone);
    }

    public void changePassword(String phone, String oldPassword, String newPassword) {
        User user = findPartner(phone);
        if (oldPassword == null || !encoder.matches(oldPassword, user.getPassword()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        if (newPassword == null || newPassword.length() < 8)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be at least 8 characters");
        user.setPassword(encoder.encode(newPassword)); users.save(user);
    }

    private User findPartner(String phone) {
        requirePhone(phone);
        User user = mongo.findOne(Query.query(Criteria.where("phone").is(phone)), User.class);
        if (user != null) return user;
        Map member = mongo.findOne(Query.query(Criteria.where("phone").is(phone).and("status").is("approved")), Map.class, "members");
        if (member == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approved member not found; check your name and phone number");
        user = new User();
        Object id = member.get("_id");
        user.setId(id == null ? null : String.valueOf(id));
        user.setMemberId(String.valueOf(member.getOrDefault("memberId", id == null ? "" : id)));
        user.setName(String.valueOf(member.getOrDefault("name", "")));
        user.setPhone(phone);
        user.setEmail(String.valueOf(member.getOrDefault("email", "")));
        user.setCity(String.valueOf(member.getOrDefault("city", "")));
        return user;
    }
    private Map<String, Object> publicProfile(User user) {
        return Map.of("id", user.getId(), "name", user.getName() == null ? "" : user.getName(),
                "phone", user.getPhone() == null ? "" : user.getPhone(), "email", user.getEmail() == null ? "" : user.getEmail(),
                "city", user.getCity() == null ? "" : user.getCity(), "memberId", user.getMemberId() == null ? "" : user.getMemberId());
    }
    private Map updateOwned(String collection, String id, String ownerField, String owner, Map<String, Object> data) {
        Object oid;
        try { oid = new ObjectId(id); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid record id"); }
        Query q = Query.query(Criteria.where("_id").is(oid).and(ownerField).is(owner));
        Update u = new Update(); data.remove("_id"); data.forEach(u::set); u.set("updatedAt", Instant.now().toString());
        if (mongo.updateFirst(q, u, collection).getMatchedCount() == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        return mongo.findOne(q, Map.class, collection);
    }
    private void requirePhone(String phone) {
        if (phone == null || !phone.matches("[+0-9]{8,15}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid phone number is required");
    }
    private double amount(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); } catch (RuntimeException e) { return 0; }
    }
}
