package kkdugi.core.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import tools.jackson.databind.ObjectMapper;

import kkdugi.core.security.models.SessionUser;

/**
 * {@code kkdugi_session.user_dtl}(jsonb) 컬럼 하나를 위한 전용 핸들러.
 * {@code CodeEnumTypeHandler}(모든 CodeEnums 구현 enum에 기본 적용)와 달리
 * 이 핸들러는 이 필드에만 쓰는 것이라 전역 기본값이 아니라
 * resultMap/파라미터에 {@code typeHandler=}로 명시해서 쓴다
 * (공통 규약 7번의 "one-off 매핑은 명시적으로" 원칙).
 *
 * <p>{@code org.postgresql:postgresql}은 pom.xml에 {@code runtime} 스코프로만
 * 있어(정상 — JDBC 드라이버는 애플리케이션 코드가 직접 참조할 이유가 없다)
 * 여기서 {@code org.postgresql.util.PGobject}를 쓸 수 없다(컴파일 스코프에
 * 없음). 대신 JSON 문자열을 그대로 바인딩하고, 매퍼 XML에서
 * {@code #{...}::jsonb}처럼 SQL 쪽에서 캐스팅한다(pgjdbc + jsonb의 흔한
 * 우회법).</p>
 */
public class SessionUserTypeHandler extends BaseTypeHandler<SessionUser> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, SessionUser parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, OBJECT_MAPPER.writeValueAsString(parameter));
    }

    @Override
    public SessionUser getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toSessionUser(rs.getString(columnName));
    }

    @Override
    public SessionUser getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toSessionUser(rs.getString(columnIndex));
    }

    @Override
    public SessionUser getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toSessionUser(cs.getString(columnIndex));
    }

    private SessionUser toSessionUser(String json) {
        if (json == null) {
            return null;
        }
        return OBJECT_MAPPER.readValue(json, SessionUser.class);
    }
}
