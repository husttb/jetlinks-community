package org.jetlinks.community.device.measurements.status;

import org.jetlinks.core.metadata.ConfigMetadata;
import org.jetlinks.core.metadata.DataType;
import org.jetlinks.core.metadata.DefaultConfigMetadata;
import org.jetlinks.core.metadata.types.DateTimeType;
import org.jetlinks.core.metadata.types.EnumType;
import org.jetlinks.core.metadata.types.IntType;
import org.jetlinks.core.metadata.types.StringType;
import org.jetlinks.community.dashboard.*;
import org.jetlinks.community.dashboard.supports.StaticMeasurement;
import org.jetlinks.community.device.entity.DeviceInstanceEntity;
import org.jetlinks.community.device.enums.DeviceState;
import org.jetlinks.community.device.service.LocalDeviceInstanceService;
import org.jetlinks.community.device.timeseries.DeviceTimeSeriesMetric;
import org.jetlinks.community.timeseries.TimeSeriesManager;
import org.jetlinks.community.timeseries.query.AggregationQueryParam;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

class DeviceStatusRecordMeasurement
    extends StaticMeasurement {

    public LocalDeviceInstanceService instanceService;

    private TimeSeriesManager timeSeriesManager;

    static MeasurementDefinition definition = MeasurementDefinition.of("record", "设备状态记录");

    public DeviceStatusRecordMeasurement(LocalDeviceInstanceService deviceInstanceService,
                                         TimeSeriesManager timeSeriesManager) {
        super(definition);
        this.timeSeriesManager = timeSeriesManager;
        this.instanceService = deviceInstanceService;
        addDimension(new CurrentNumberOfDeviceDimension());
        addDimension(new AggNumberOfOnlineDeviceDimension());
    }

    static ConfigMetadata aggConfigMetadata = new DefaultConfigMetadata()
        .add("time", "周期", "例如: 1h,10m,30s", new StringType())
        .add("format", "时间格式", "如: MM-dd:HH", new StringType())
        .add("limit", "最大数据量", "", new IntType())
        .add("from", "时间从", "", new DateTimeType())
        .add("to", "时间至", "", new DateTimeType());


    //历史在线数量
    class AggNumberOfOnlineDeviceDimension implements MeasurementDimension {

        @Override
        public DimensionDefinition getDefinition() {
            return DimensionDefinition.of("aggOnline", "历史在线数");
        }

        @Override
        public DataType getValueType() {
            return new IntType();
        }

        @Override
        public ConfigMetadata getParams() {
            return aggConfigMetadata;
        }

        @Override
        public boolean isRealTime() {
            return false;
        }

        @Override
        public Flux<MeasurementValue> getValue(MeasurementParameter parameter) {
            return AggregationQueryParam.of()
                .max("value")
                .filter(query -> query.where("name", "online-count"))
                .from(parameter.getDate("from").orElse(Date.from(LocalDateTime.now().plusDays(-30).atZone(ZoneId.systemDefault()).toInstant())))
                .to(parameter.getDate("to").orElse(new Date()))
                .groupBy(parameter.getDuration("time").orElse(Duration.ofDays(1)),
                    parameter.getString("format").orElse("yyyy-MM-dd"))
                .limit(parameter.getInt("limit").orElse(10))
                .execute(timeSeriesManager.getService(DeviceTimeSeriesMetric.deviceMetrics())::aggregation)
                .map(data ->
                    SimpleMeasurementValue.of(data.getInt("value").orElse(0),
                        data.getString("time").orElse("-"),
                        System.currentTimeMillis()));
        }
    }

    static ConfigMetadata currentMetadata = new DefaultConfigMetadata()
        .add("productId", "设备型号", "", new StringType())
        .add("state", "状态", "online", new EnumType()
            .addElement(EnumType.Element.of(DeviceState.online.getValue(), DeviceState.online.getText()))
            .addElement(EnumType.Element.of(DeviceState.offline.getValue(), DeviceState.offline.getText()))
            .addElement(EnumType.Element.of(DeviceState.notActive.getValue(), DeviceState.notActive.getText()))
        );

    //当前设备数量
    class CurrentNumberOfDeviceDimension implements MeasurementDimension {

        @Override
        public DimensionDefinition getDefinition() {
            return CommonDimensionDefinition.current;
        }

        @Override
        public DataType getValueType() {
            return new IntType();
        }

        @Override
        public ConfigMetadata getParams() {
            return currentMetadata;
        }

        @Override
        public boolean isRealTime() {
            return false;
        }

        @Override
        public Mono<MeasurementValue> getValue(MeasurementParameter parameter) {
            return instanceService
                .createQuery()
                .and(DeviceInstanceEntity::getProductId, parameter.getString("productId").orElse(null))
                .and(DeviceInstanceEntity::getState, parameter.get("state", DeviceState.class).orElse(null))
                .count()
                .map(val -> SimpleMeasurementValue.of(val, System.currentTimeMillis()));
        }
    }


}