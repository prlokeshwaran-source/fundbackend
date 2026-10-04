package com.example.rkfund.repository;

import com.example.rkfund.entity.User;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<User, String> {

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    java.util.Optional<User> findByEmail(String email);

    java.util.Optional<User> findByNameAndPhone(String name, String phone);
}
