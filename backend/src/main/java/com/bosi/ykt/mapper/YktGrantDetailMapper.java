package com.bosi.ykt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bosi.ykt.entity.YktGrantDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface YktGrantDetailMapper extends BaseMapper<YktGrantDetail> {

    /**
     * 惠民报表-乡镇项目发放情况：按批次聚合已支付明细（发放金额/人数）+ 批次计划（申请金额/人数）。
     * SQL 端 group by，覆盖全部已支付明细对应的批次；口径与个人查询一致（payStatus=已支付）。
     */
    @Select("SELECT b.PROJECT_ID AS projectId, b.TOWN_ID AS townId, b.BATCH_NAME AS batchName, "
            + "TO_CHAR(b.GRANT_TIME,'YYYY-MM') AS mon, TO_CHAR(b.GRANT_TIME,'YYYY-MM-DD') AS grantDate, "
            + "b.PLAN_AMOUNT AS applyAmount, b.PLAN_COUNT AS applyCount, "
            + "COUNT(d.ID) AS grantCount, NVL(SUM(d.AMOUNT),0) AS grantAmount "
            + "FROM YKT_BATCH b JOIN YKT_GRANT_DETAIL d ON d.BATCH_ID = b.ID "
            + "WHERE d.PAY_STATUS = '已支付' AND d.DELETED = 0 AND b.DELETED = 0 "
            + "AND (#{tid} IS NULL OR b.TENANT_ID = #{tid}) "
            + "AND (#{projectId} IS NULL OR b.PROJECT_ID = #{projectId}) "
            + "AND (#{batchId} IS NULL OR b.ID = #{batchId}) "
            + "AND (#{townId} IS NULL OR b.TOWN_ID = #{townId}) "
            + "AND (#{competentDept} IS NULL OR b.PROJECT_ID IN (SELECT ID FROM YKT_PROJECT WHERE COMPETENT_DEPT = #{competentDept})) "
            + "GROUP BY b.PROJECT_ID, b.TOWN_ID, b.ID, b.BATCH_NAME, b.GRANT_TIME, b.PLAN_AMOUNT, b.PLAN_COUNT "
            + "ORDER BY b.ID DESC")
    List<Map<String, Object>> reportBatchGrant(@Param("tid") Long tid,
                                               @Param("projectId") Long projectId,
                                               @Param("batchId") Long batchId,
                                               @Param("townId") Long townId,
                                               @Param("competentDept") String competentDept);

    /**
     * 惠民系统使用情况：按 年度+部门 统计 项目/批次/乡镇/村组/户主/享受人 个数 + 发放金额。
     * 数据源 payStatus=已支付，可按发放日期范围与部门过滤。
     */
    @Select("<script>SELECT TO_CHAR(b.GRANT_TIME,'YYYY') AS yr, p.COMPETENT_DEPT AS dept, "
            + "COUNT(DISTINCT b.PROJECT_ID) AS projectCnt, COUNT(DISTINCT b.ID) AS batchCnt, "
            + "COUNT(DISTINCT b.TOWN_ID) AS townCnt, COUNT(DISTINCT d.VILLAGE_NAME) AS villageCnt, "
            + "COUNT(DISTINCT d.HOLDER_ID_CARD) AS holderCnt, COUNT(d.ID) AS beneficiaryCnt, "
            + "NVL(SUM(d.AMOUNT),0) AS amount, COUNT(d.ID) AS actualCnt, NVL(SUM(d.AMOUNT),0) AS actualAmount "
            + "FROM YKT_BATCH b JOIN YKT_GRANT_DETAIL d ON d.BATCH_ID = b.ID JOIN YKT_PROJECT p ON b.PROJECT_ID = p.ID "
            + "WHERE d.PAY_STATUS = '已支付' AND d.DELETED = 0 AND b.DELETED = 0 AND p.DELETED = 0 "
            + "AND (#{tid} IS NULL OR b.TENANT_ID = #{tid}) "
            + "AND (#{startDate} IS NULL OR b.GRANT_TIME &gt;= TO_DATE(#{startDate},'YYYY-MM-DD')) "
            + "AND (#{endDate} IS NULL OR b.GRANT_TIME &lt; TO_DATE(#{endDate},'YYYY-MM-DD') + 1) "
            + "AND (#{competentDept} IS NULL OR p.COMPETENT_DEPT = #{competentDept}) "
            // 县域隔离：null=不限（ALL）；空集在调用侧兜为 [-1]（零结果）
            + "<if test='townIds != null'> AND b.TOWN_ID IN "
            + "<foreach item='t' collection='townIds' open='(' separator=',' close=')'>#{t}</foreach></if> "
            + "GROUP BY TO_CHAR(b.GRANT_TIME,'YYYY'), p.COMPETENT_DEPT "
            + "ORDER BY yr DESC</script>")
    List<Map<String, Object>> reportUsage(@Param("tid") Long tid,
                                          @Param("startDate") String startDate,
                                          @Param("endDate") String endDate,
                                          @Param("competentDept") String competentDept,
                                          @Param("townIds") List<Long> townIds);
}
