package kkdugi.core.serial.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SerialMapper {

    /**
     * Get serial value
     * 
     * @param id   Serial ID
     * @param key  Serial key
     * @param size Serial size
     * @return Serial value
     */
    Long getSerial(@Param("id") String id, @Param("key") String key, @Param("size") long size);
}
