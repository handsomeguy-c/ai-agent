package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ChunkBgeM3;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChunkBgeM3Mapper {
    int insert(ChunkBgeM3 chunkBgeM3);

    ChunkBgeM3 selectById(String id);

    int deleteById(String id);

    int updateById(ChunkBgeM3 chunkBgeM3);

    List<ChunkBgeM3> similaritySearch(
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    List<ChunkBgeM3> keywordSearch(
            @Param("kbId") String kbId,
            @Param("query") String query,
            @Param("keywords") List<String> keywords,
            @Param("limit") int limit
    );

    List<ChunkBgeM3> selectRecentByKbId(
            @Param("kbId") String kbId,
            @Param("limit") int limit
    );
}
