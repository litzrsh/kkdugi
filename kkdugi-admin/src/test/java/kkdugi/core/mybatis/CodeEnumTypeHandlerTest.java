package kkdugi.core.mybatis;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import kkdugi.core.enums.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeEnumTypeHandlerTest {

    private final CodeEnumTypeHandler<UserStatus> handler = new CodeEnumTypeHandler<>(UserStatus.class);

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Test
    void constructor_rejectsNullType() {
        assertThatThrownBy(() -> new CodeEnumTypeHandler<>(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setNonNullParameter_writesCode() throws Exception {
        handler.setNonNullParameter(preparedStatement, 1, UserStatus.NORM, JdbcType.VARCHAR);

        verify(preparedStatement).setString(1, "20");
    }

    @Test
    void getNullableResult_byColumnName_convertsCodeBackToEnum() throws Exception {
        when(resultSet.getString("status")).thenReturn("30");

        UserStatus result = handler.getNullableResult(resultSet, "status");

        assertThat(result).isEqualTo(UserStatus.DORM);
    }

    @Test
    void getNullableResult_byColumnIndex_convertsCodeBackToEnum() throws Exception {
        when(resultSet.getString(2)).thenReturn("50");

        UserStatus result = handler.getNullableResult(resultSet, 2);

        assertThat(result).isEqualTo(UserStatus.SUPD);
    }

    @Test
    void getNullableResult_returnsNull_whenColumnIsNull() throws Exception {
        when(resultSet.getString("status")).thenReturn(null);

        assertThat(handler.getNullableResult(resultSet, "status")).isNull();
    }
}
