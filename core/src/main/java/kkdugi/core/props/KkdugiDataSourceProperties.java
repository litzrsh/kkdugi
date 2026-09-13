package kkdugi.core.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@ConfigurationProperties(prefix = "kkdugi.datasource")
public class KkdugiDataSourceProperties {

    private String primaryKey;
}
