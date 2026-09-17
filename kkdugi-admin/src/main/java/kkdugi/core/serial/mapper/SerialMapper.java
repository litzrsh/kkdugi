package kkdugi.core.serial.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SerialMapper {

    /**
     * upsertAndGetNext
     * Database function을 호출하여 일련번호 채번
     * 
     * @param id    SERIAL_ID
     * @param key   SERIAL_KEY
     * @Param size
     */
    Long upsertAndGetNext(
            @Param("id") String id,
            @Param("key") String key,
            @Param("size") long size);
}
