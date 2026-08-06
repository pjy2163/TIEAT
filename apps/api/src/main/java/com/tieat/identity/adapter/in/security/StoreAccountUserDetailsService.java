package com.tieat.identity.adapter.in.security;

import com.tieat.identity.domain.StoreAccountRepository;
import java.util.Objects;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class StoreAccountUserDetailsService implements UserDetailsService {

    private final StoreAccountRepository storeAccountRepository;

    public StoreAccountUserDetailsService(StoreAccountRepository storeAccountRepository) {
        this.storeAccountRepository = Objects.requireNonNull(storeAccountRepository);
    }

    @Override
    public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        return storeAccountRepository.findByLoginId(loginId)
            .map(StoreAccountPrincipal::new)
            .orElseThrow(() -> new UsernameNotFoundException("Store account not found"));
    }
}
