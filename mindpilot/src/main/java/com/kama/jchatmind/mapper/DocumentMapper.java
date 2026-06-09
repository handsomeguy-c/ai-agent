package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.Document;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DocumentMapper {
    int insert(Document document);

    Document selectById(String id);

    List<Document> selectAll();

    List<Document> selectByKbId(String kbId);

    int deleteById(String id);

    int updateById(Document document);
}
