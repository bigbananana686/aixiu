package com.autocare.server.security;

import com.autocare.common.error.*;
import com.autocare.common.identity.Role;
import com.autocare.server.user.*;
import org.springframework.stereotype.Component;

@Component
public class AdminAccess {

  private final UserMapper users;

  public AdminAccess(UserMapper users) {
    this.users = users;
  }

  public void require(long actor) {
    UserEntity u = users.findById(actor);
    if (u == null || !u.isEnabled()) throw ApiException.of(
      ErrorCode.UNAUTHENTICATED
    );
    if (u.getRole() != Role.ADMIN) throw ApiException.of(ErrorCode.FORBIDDEN);
  }
}
