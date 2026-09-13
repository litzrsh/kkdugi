package kkdugi.core.models;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;

import kkdugi.core.Constants;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseModel implements Serializable {

    @Serial
    private static final long serialVersionUID = Constants.SERIAL_ID;

    @JsonIgnore
    private Long totalItems;
    @JsonProperty(access = Access.READ_ONLY)
    private Long rownum;

    @JsonIgnore
    private String createId;
    private Date createdAt;
    @JsonIgnore
    private String updateId;
    private Date updatedAt;

    public String getCreatedBy() {
        return null;
    }

    public String getUpdatedBy() {
        return null;
    }
}
