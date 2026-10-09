package com.yupi.yupicturebackend.mapper;

import com.yupi.yupicturebackend.model.entity.Picture;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
* @author 李鱼皮
* @description 针对表【picture(图片)】的数据库操作Mapper
* @createDate 2024-12-11 20:45:51
* @Entity com.yupi.yupicturebackend.model.entity.Picture
*/
public interface PictureMapper extends BaseMapper<Picture> {
    @Select("SELECT * FROM picture WHERE id = #{id} AND isDelete = 0 FOR UPDATE")
    Picture selectForUpdate(@Param("id") long id);
}




