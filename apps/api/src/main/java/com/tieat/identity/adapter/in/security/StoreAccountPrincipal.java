package com.tieat.identity.adapter.in.security;

import com.tieat.identity.domain.StoreAccount;
import com.tieat.store.domain.StoreId;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

public final class StoreAccountPrincipal implements UserDetails {

    private final String loginId;
    private final String passwordHash;
    private final StoreId storeId;
    private final boolean enabled;

    public StoreAccountPrincipal(StoreAccount account) {
        this.loginId = account.loginId();
        this.passwordHash = account.passwordHash();
        this.storeId = account.storeId();
        this.enabled = account.enabled();
    }

    public StoreId storeId() {
        return storeId;
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
}
