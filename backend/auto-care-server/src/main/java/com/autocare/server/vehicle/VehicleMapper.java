package com.autocare.server.vehicle;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface VehicleMapper {

    @Select("SELECT id, owner_id, model_code, plate_mask_source, plate_masked, mileage, row_version, " +
            "created_at, updated_at FROM t_vehicle WHERE owner_id = #{ownerId} " +
            "ORDER BY id ASC LIMIT #{limit} OFFSET #{offset}")
    List<VehicleEntity> listByOwner(@Param("ownerId") long ownerId,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM t_vehicle WHERE owner_id = #{ownerId}")
    long countByOwner(@Param("ownerId") long ownerId);

    @Select("SELECT id, owner_id, model_code, plate_mask_source, plate_masked, mileage, row_version, " +
            "created_at, updated_at FROM t_vehicle WHERE id = #{id} AND owner_id = #{ownerId}")
    VehicleEntity findOwned(@Param("id") long id, @Param("ownerId") long ownerId);

    @Insert("INSERT INTO t_vehicle (owner_id, model_code, plate_mask_source, plate_masked, mileage, " +
            "row_version, created_at, updated_at) VALUES (#{ownerId}, #{modelCode}, #{plateMaskSource}, " +
            "#{plateMasked}, #{mileage}, #{rowVersion}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(VehicleEntity entity);
    @Update("""
        UPDATE t_vehicle
        SET model_code = #{vehicle.modelCode},
            plate_mask_source = #{vehicle.plateMaskSource},
            plate_masked = #{vehicle.plateMasked},
            mileage = #{vehicle.mileage},
            row_version = row_version + 1,
            updated_at = #{vehicle.updatedAt}
        WHERE id = #{vehicle.id}
          AND owner_id = #{vehicle.ownerId}
          AND row_version = #{expectedVersion}
        """)
    int updateOwned(
            @Param("vehicle") VehicleEntity vehicle,
            @Param("expectedVersion") long expectedVersion);
}
