package kkdugi.app.batch.mapper;

import org.apache.ibatis.annotations.Mapper;

import kkdugi.app.batch.models.BatchEvent;

@Mapper
public interface BatchEventMapper {

    int insert(BatchEvent row);
}
