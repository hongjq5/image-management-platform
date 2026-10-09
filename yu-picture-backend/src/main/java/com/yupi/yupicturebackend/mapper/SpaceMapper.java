package com.yupi.yupicturebackend.mapper;

import com.yupi.yupicturebackend.model.entity.Space;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
* @author 李鱼皮
* @description 针对表【space(空间)】的数据库操作Mapper
* @createDate 2024-12-18 19:53:34
* @Entity com.yupi.yupicturebackend.model.entity.Space
*/
public interface SpaceMapper extends BaseMapper<Space> {
    @Update("UPDATE space SET totalSize = totalSize + #{sizeDelta}, totalCount = totalCount + #{countDelta} "
            + "WHERE id = #{id} AND isDelete = 0 "
            + "AND totalSize + #{sizeDelta} >= 0 AND totalCount + #{countDelta} >= 0 "
            + "AND (#{sizeDelta} <= 0 OR totalSize + #{sizeDelta} <= maxSize) "
            + "AND (#{countDelta} <= 0 OR totalCount + #{countDelta} <= maxCount)")
    int adjustUsage(@Param("id") long id, @Param("sizeDelta") long sizeDelta,
                    @Param("countDelta") long countDelta);
}




