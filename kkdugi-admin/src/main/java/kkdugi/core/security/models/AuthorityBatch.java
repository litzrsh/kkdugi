package kkdugi.core.security.models;

import java.util.List;

/** Batch commands expose their changes for authorization before any mutation. */
public interface AuthorityBatch {
    List<?> getInsert();
    List<?> getUpdate();
    List<?> getDelete();
}
