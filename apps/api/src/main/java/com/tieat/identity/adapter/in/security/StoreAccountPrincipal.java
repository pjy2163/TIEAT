package com.tieat.identity.adapter.in.security;

import com.tieat.identity.domain.StoreAccount;
import com.tieat.store.domain.StoreId;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.userdetails.UserDetails;

public final class StoreAccountPrincipal implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String loginId;
    private final UUID storeId;
    private final boolean enabled;
    private transient String passwordHash;

    public StoreAccountPrincipal(StoreAccount account) {
        this.loginId = account.loginId();
        this.passwordHash = account.passwordHash();
        this.storeId = account.storeId().value();
        this.enabled = account.enabled();
    }

    public StoreId storeId() {
        return new StoreId(storeId);
    }

    public String loginId() {
        return loginId;
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AuthorityUtils.createAuthorityList("ROLE_STORE_STAFF");
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginId;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
