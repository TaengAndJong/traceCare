package com.tracecare.backend.domain.auth.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tracecare.backend.common.exception.business.UserNotFoundException;
import com.tracecare.backend.domain.auth.entity.User;
import com.tracecare.backend.domain.auth.repository.UserRepository;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public User getUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
    }

    @Transactional
    public User confirmRole(Long userId, String role, String name, LocalDate birthDate) {
        User user = getUser(userId);
        user.confirmRole(role, name, birthDate);
        return user;
    }

    /** Guardian/CareTarget 공용 — API_Specification.md §3.8, §4.6. 두 Role Controller가 그대로 재사용한다. */
    @Transactional
    public User updateProfile(Long userId, String name, String phone) {
        User user = getUser(userId);
        user.updateProfile(name, phone);
        return user;
    }

    @Transactional
    public User updateProfileImage(Long userId, String imageUrl) {
        User user = getUser(userId);
        user.updateProfileImage(imageUrl);
        return user;
    }
}
