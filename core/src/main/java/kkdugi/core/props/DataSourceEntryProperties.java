package kkdugi.core.props;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceEntryProperties {

    private String driverClassName;
    private String url;
    private String username;
    private String password;
    private String mappers;
}
