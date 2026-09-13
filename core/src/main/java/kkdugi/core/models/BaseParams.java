package kkdugi.core.models;

import java.io.Serial;
import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonProperty;

import kkdugi.core.Constants;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseParams implements Serializable {

    @Serial
    private static final long serialVersionUID = Constants.SERIAL_ID;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int page = 1;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private int pageSize = 200;
}
