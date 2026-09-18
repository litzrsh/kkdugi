package kkdugi.core.models;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class BaseModel {

    @JsonIgnore
    private Long totalSize;
    private Long rownum;
    private LocalDateTime createdAt;
    private String creatorId;
    private LocalDateTime updatedAt;
    private String updaterId;
}
