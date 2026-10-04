package com.example.rkfund.service;

import com.example.rkfund.dto.LoginRequest;
import com.example.rkfund.dto.LoginResponse;
import com.example.rkfund.entity.User;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import java.util.Map;

@Service
public class UserService {

    private final MongoTemplate mongo;

    public UserService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public LoginResponse login(LoginRequest request) {
        String name = clean(request.getName());
        String phone = clean(request.getPhone());
        if (name == null || phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide name and phone number");
        }
        Map member = mongo.findOne(Query.query(Criteria.where("name").is(name)
                .and("phone").is(phone).and("status").is("approved")), Map.class, "members");
        if (member == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member is not approved or details are incorrect");
        Object id = member.get("_id");
        Object memberId = member.get("memberId");
        if (memberId == null) memberId = id;
        Object memberEmail = member.get("email");
        User user = new User();
        user.setId(id == null ? null : String.valueOf(id));
        user.setMemberId(memberId == null ? null : String.valueOf(memberId));
        user.setName(name);
        user.setPhone(phone);
        user.setEmail(memberEmail == null ? "" : String.valueOf(memberEmail));
        return loginResponse(user);
    }

    private LoginResponse loginResponse(User user) {
        String orderId = null;
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            Map order = mongo.findOne(Query.query(Criteria.where("customerPhone").is(user.getPhone()))
                    .with(Sort.by(Sort.Direction.DESC, "createdAt")), Map.class, "orders");
            if (order != null) {
                Object foundOrderId = order.get("orderId");
                if (foundOrderId == null) foundOrderId = order.get("_id");
                if (foundOrderId != null) orderId = String.valueOf(foundOrderId);
            }
        }
        return new LoginResponse(user.getMemberId() == null ? user.getId() : user.getMemberId(),
                user.getName(), user.getEmail(), user.getPhone(), orderId);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
