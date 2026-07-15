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

    /**
     * 财政报表-分项目发放情况统计：按项目汇总 兑付县区数/兑付人次/兑付金额。
     * 县区 = 乡镇 orgCode 前 6 位；日期范围按批次发放时间。
     */
    @Select("<script>SELECT p.ID AS projectId, p.PROJECT_CODE AS projectCode, p.PROJECT_NAME AS projectName, "
            + "COUNT(DISTINCT SUBSTR(t.ORG_CODE,1,6)) AS countyCnt, "
            + "COUNT(d.ID) AS personCnt, NVL(SUM(d.AMOUNT),0) AS amount "
            + "FROM YKT_BATCH b JOIN YKT_GRANT_DETAIL d ON d.BATCH_ID = b.ID "
            + "JOIN YKT_PROJECT p ON p.ID = b.PROJECT_ID JOIN SYS_ORG t ON t.ID = b.TOWN_ID "
            + "WHERE d.PAY_STATUS = '已支付' AND d.DELETED = 0 AND b.DELETED = 0 AND p.DELETED = 0 "
            + "AND (#{tid} IS NULL OR b.TENANT_ID = #{tid}) "
            + "AND (#{projectId} IS NULL OR p.ID = #{projectId}) "
            + "AND (#{startDate} IS NULL OR b.GRANT_TIME &gt;= TO_DATE(#{startDate},'YYYY-MM-DD')) "
            + "AND (#{endDate} IS NULL OR b.GRANT_TIME &lt; TO_DATE(#{endDate},'YYYY-MM-DD') + 1) "
            + "<if test='townIds != null'> AND b.TOWN_ID IN "
            + "<foreach item='t' collection='townIds' open='(' separator=',' close=')'>#{t}</foreach></if> "
            + "GROUP BY p.ID, p.PROJECT_CODE, p.PROJECT_NAME "
            + "ORDER BY p.PROJECT_CODE</script>")
    List<Map<String, Object>> reportProjectStat(@Param("tid") Long tid,
                                                @Param("projectId") Long projectId,
                                                @Param("startDate") String startDate,
                                                @Param("endDate") String endDate,
                                                @Param("townIds") List<Long> townIds);

    /**
     * 财政报表-县区×项目 聚合：下达批次数/兑付人次/兑付金额。
     * 供 分县区统计表(全部项目)、51项发放表(stdOnly=国标目录内)、自设项目发放表(selfOnly=9 开头) 复用。
     */
    @Select("<script>SELECT SUBSTR(t.ORG_CODE,1,6) AS countyCode, "
            + "p.ID AS projectId, p.PROJECT_CODE AS projectCode, p.PROJECT_NAME AS projectName, "
            + "COUNT(DISTINCT b.ID) AS batchCnt, COUNT(d.ID) AS personCnt, NVL(SUM(d.AMOUNT),0) AS amount "
            + "FROM YKT_BATCH b JOIN YKT_GRANT_DETAIL d ON d.BATCH_ID = b.ID "
            + "JOIN YKT_PROJECT p ON p.ID = b.PROJECT_ID JOIN SYS_ORG t ON t.ID = b.TOWN_ID "
            + "WHERE d.PAY_STATUS = '已支付' AND d.DELETED = 0 AND b.DELETED = 0 AND p.DELETED = 0 "
            + "AND (#{tid} IS NULL OR b.TENANT_ID = #{tid}) "
            + "AND (#{projectId} IS NULL OR p.ID = #{projectId}) "
            + "AND (#{projectCode} IS NULL OR p.PROJECT_CODE = #{projectCode}) "
            + "AND (#{countyCode} IS NULL OR SUBSTR(t.ORG_CODE,1,6) = #{countyCode}) "
            + "AND (#{startDate} IS NULL OR b.GRANT_TIME &gt;= TO_DATE(#{startDate},'YYYY-MM-DD')) "
            + "AND (#{endDate} IS NULL OR b.GRANT_TIME &lt; TO_DATE(#{endDate},'YYYY-MM-DD') + 1) "
            + "<if test='stdOnly'> AND p.PROJECT_CODE IN (SELECT CODE FROM YKT_STD_ITEM)</if> "
            + "<if test='selfOnly'> AND p.PROJECT_CODE LIKE '9%'</if> "
            + "<if test='townIds != null'> AND b.TOWN_ID IN "
            + "<foreach item='t' collection='townIds' open='(' separator=',' close=')'>#{t}</foreach></if> "
            + "GROUP BY SUBSTR(t.ORG_CODE,1,6), p.ID, p.PROJECT_CODE, p.PROJECT_NAME "
            + "ORDER BY SUBSTR(t.ORG_CODE,1,6), p.PROJECT_CODE</script>")
    List<Map<String, Object>> reportCountyProject(@Param("tid") Long tid,
                                                  @Param("projectId") Long projectId,
                                                  @Param("projectCode") String projectCode,
                                                  @Param("countyCode") String countyCode,
                                                  @Param("startDate") String startDate,
                                                  @Param("endDate") String endDate,
                                                  @Param("stdOnly") boolean stdOnly,
                                                  @Param("selfOnly") boolean selfOnly,
                                                  @Param("townIds") List<Long> townIds);
}
