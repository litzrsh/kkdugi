package kkdugi.core.security.service;

import java.util.List;

import org.springframework.stereotype.Service;

import kkdugi.core.security.mapper.SecurityUserDetailsMapper;
import kkdugi.core.security.mapper.SysAdminMenuMapper;
import kkdugi.core.security.models.SessionMenu;

/**
 * SYS_ADMIN 세션 메뉴 로딩 — {@link KkdugiUserDetailsService}(일반 사용자
 * 로그인/세션 로딩)와 분리된 관리자 전용 기능. {@code KkdugiUserDetailsService}는
 * 이미 로드한 authorities로 SYS_ADMIN 여부를 판단한 뒤 이 서비스를 호출할지,
 * 아니면 {@link SecurityUserDetailsMapper#findMenusByUsername}(일반 경로)를
 * 쓸지만 결정한다 — SQL/매퍼 세부사항은 여기 안에 숨어 있다.
 */
@Service
public class SysAdminMenuService {

    private final SysAdminMenuMapper mapper;

    public SysAdminMenuService(SysAdminMenuMapper mapper) {
        this.mapper = mapper;
    }

    public List<SessionMenu> findAllMenus(String langCode) {
        return mapper.findAllMenus(langCode);
    }
}
