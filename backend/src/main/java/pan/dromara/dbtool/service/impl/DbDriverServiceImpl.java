package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbDriverManager;
import pan.dromara.dbtool.core.DbTypeInfo;
import pan.dromara.dbtool.core.DbTypeRegistry;
import pan.dromara.dbtool.domain.DbDriver;
import pan.dromara.dbtool.mapper.DbDriverMapper;
import pan.dromara.dbtool.service.IDbDriverService;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 数据库驱动 服务实现
 *
 * @author doubao
 */
@RequiredArgsConstructor
@Service
public class DbDriverServiceImpl implements IDbDriverService {

    private final DbDriverMapper dbDriverMapper;
    private final DbDriverManager driverManager;

    /**
     * 启动时登记内置驱动(source=1)，驱动列表展示 MySQL/PostgreSQL 等已集成驱动
     */
    @PostConstruct
    public void initBuiltinDrivers() {
        for (DbTypeInfo info : DbTypeRegistry.listAll()) {
            if (!Boolean.TRUE.equals(info.getBuiltin())) {
                continue;
            }
            DbDriver exist = dbDriverMapper.selectOne(new LambdaQueryWrapper<DbDriver>()
                .eq(DbDriver::getDbType, info.getCode())
                .eq(DbDriver::getSource, "1")
                .last("LIMIT 1"));
            if (exist != null) {
                continue;
            }
            DbDriver record = new DbDriver();
            record.setDbType(info.getCode());
            record.setDriverName(info.getArtifact());
            record.setDriverVersion(builtinVersionOf(info.getCode()));
            record.setDriverClass(info.getDriverClass());
            record.setJarPath("");
            record.setFileSize(0L);
            record.setSource("1");
            record.setStatus("0");
            dbDriverMapper.insert(record);
        }
    }

    private String builtinVersionOf(String typeCode) {
        // MySQL 协议族内置 mysql-connector-j 9.7.0；PostgreSQL 协议族内置 postgresql 42.7.11
        if (List.of("POSTGRESQL", "COCKROACHDB", "YUGABYTEDB", "GREENPLUM", "EDB", "TIMESCALEDB").contains(typeCode)) {
            return "42.7.11(内置)";
        }
        return "9.7.0(内置)";
    }

    @Override
    public Page<DbDriver> pageList(Integer pageNum, Integer pageSize, String dbType, String keyword) {
        LambdaQueryWrapper<DbDriver> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(StrUtil.isNotBlank(keyword), w -> w
                .like(DbDriver::getDriverName, keyword)
                .or().like(DbDriver::getDriverVersion, keyword))
            .orderByDesc(DbDriver::getCreateTime);
        // 数据库类型支持多选（逗号分隔）
        if (StrUtil.isNotBlank(dbType)) {
            List<String> types = StrUtil.split(dbType, ',').stream()
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .map(String::toUpperCase)
                .toList();
            if (!types.isEmpty()) {
                wrapper.in(DbDriver::getDbType, types);
            }
        }
        return dbDriverMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public List<DbDriver> listAll() {
        return dbDriverMapper.selectList(new LambdaQueryWrapper<DbDriver>()
            .orderByAsc(DbDriver::getDbType));
    }

    @Override
    public DbDriver download(String dbType, String version) {
        DbTypeInfo info = DbTypeRegistry.of(dbType);
        if (info == null) {
            throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }
        if (Boolean.TRUE.equals(info.getBuiltin())) {
            throw new IllegalArgumentException("【" + info.getName() + "】驱动已内置集成，无需下载");
        }
        // 已存在同类型同版本则不重复下载
        DbDriver exist = findOne(info.getCode(), version);
        if (exist != null) {
            return exist;
        }
        File jar = driverManager.downloadDriver(info.getCode(), version);
        DbDriver record = buildRecord(info, version, jar);
        record.setSource("2");
        dbDriverMapper.insert(record);
        return record;
    }

    @Override
    public DbDriver upload(String dbType, String version, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的驱动 jar 文件");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("仅支持 .jar 驱动文件");
        }
        DbTypeInfo info = DbTypeRegistry.of(dbType);
        String typeCode = info != null ? info.getCode() : StrUtil.blankToDefault(dbType, "CUSTOM").toUpperCase();
        String ver = StrUtil.blankToDefault(version, "custom");
        DbDriver exist = findOne(typeCode, ver);
        File jar;
        try {
            jar = driverManager.saveUploadedDriver(typeCode, ver, file.getInputStream(), filename);
        } catch (IOException e) {
            throw new RuntimeException("驱动保存失败: " + e.getMessage(), e);
        }
        DbDriver record = new DbDriver();
        record.setDbType(typeCode);
        record.setDriverName(info != null ? info.getArtifact() : filename.replaceAll("\\.jar$", ""));
        record.setDriverVersion(ver);
        record.setDriverClass(info != null ? info.getDriverClass() : "");
        record.setJarPath(jar.getAbsolutePath());
        record.setSource("3");
        record.setFileSize(jar.length());
        record.setStatus("0");
        if (exist != null) {
            record.setId(exist.getId());
            dbDriverMapper.updateById(record);
            return record;
        }
        dbDriverMapper.insert(record);
        return record;
    }

    @Override
    public boolean remove(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        for (Long id : ids) {
            DbDriver driver = dbDriverMapper.selectById(id);
            if (driver != null) {
                if ("1".equals(driver.getSource())) {
                    throw new IllegalArgumentException("内置驱动不可删除");
                }
                driverManager.deleteDriver(driver.getDbType());
                dbDriverMapper.deleteById(id);
            }
        }
        return true;
    }

    @Override
    public boolean update(DbDriver driver) {
        if (driver == null || driver.getId() == null) {
            return false;
        }
        DbDriver exist = dbDriverMapper.selectById(driver.getId());
        if (exist == null) {
            return false;
        }
        if ("1".equals(exist.getSource())) {
            // 内置驱动: 名称/版本/驱动类由注册表固定, 仅允许修改状态/备注
            DbDriver upd = new DbDriver();
            upd.setId(driver.getId());
            upd.setStatus(driver.getStatus());
            upd.setRemark(driver.getRemark());
            return dbDriverMapper.updateById(upd) > 0;
        }
        return dbDriverMapper.updateById(driver) > 0;
    }

    private DbDriver findOne(String dbType, String version) {
        return dbDriverMapper.selectOne(new LambdaQueryWrapper<DbDriver>()
            .eq(DbDriver::getDbType, dbType)
            .eq(StrUtil.isNotBlank(version), DbDriver::getDriverVersion, version)
            .last("LIMIT 1"));
    }

    private DbDriver buildRecord(DbTypeInfo info, String version, File jar) {
        DbDriver record = new DbDriver();
        record.setDbType(info.getCode());
        record.setDriverName(info.getArtifact());
        record.setDriverVersion(StrUtil.blankToDefault(version, info.getDefaultVersion()));
        record.setDriverClass(info.getDriverClass());
        record.setJarPath(jar.getAbsolutePath());
        record.setFileSize(jar.length());
        record.setStatus("0");
        return record;
    }
}
