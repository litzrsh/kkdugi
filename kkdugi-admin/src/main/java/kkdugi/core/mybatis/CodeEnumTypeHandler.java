package kkdugi.core.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import kkdugi.core.enums.CodeEnums;

/**
 * {@link CodeEnums}를 구현하는 모든 enum을 {@code getCode()} 문자열로
 * DB에 저장/조회하는 범용 핸들러. 이 클래스를 컬럼마다 {@code typeHandler=}로
 * 지정할 필요는 없다 — {@code application.yml}의
 * {@code mybatis.configuration.default-enum-type-handler}로 한 번만
 * 등록해두면, {@link CodeEnums}를 구현한 모든 enum 필드/파라미터에
 * MyBatis가 자동으로 이 핸들러를 적용한다(MyBatis가 enum 타입마다
 * {@code Class<E>}를 받는 생성자로 인스턴스를 새로 만들어 쓴다 —
 * {@code org.apache.ibatis.type.EnumTypeHandler}와 같은 방식).
 *
 * <p>{@link CodeEnums}를 구현하지 않은 enum까지 이 핸들러가 기본으로
 * 잡을 수 있으므로, 이 프로젝트의 규약상 DB에 저장되는 코드성 enum은
 * 항상 {@link CodeEnums}를 구현해야 한다.</p>
 */
public class CodeEnumTypeHandler<E extends Enum<E> & CodeEnums> extends BaseTypeHandler<E> {

    private final Class<E> type;

    public CodeEnumTypeHandler(Class<E> type) {
        if (type == null) {
            throw new IllegalArgumentException("type 인자는 null일 수 없습니다");
        }
        this.type = type;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, E parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.getCode());
    }

    @Override
    public E getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toEnum(rs.getString(columnName));
    }

    @Override
    public E getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toEnum(rs.getString(columnIndex));
    }

    @Override
    public E getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toEnum(cs.getString(columnIndex));
    }

    private E toEnum(String code) {
        if (code == null) {
            return null;
        }
        return CodeEnums.fromCode(type, code);
    }
}
