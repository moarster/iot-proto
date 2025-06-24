package ru.iteco.opcua.model

enum class MeterType {
    //ProtoTypes
    HOT_WATER_FLOW,
    HOT_WATER_TEMPERATURE,
    COLD_WATER_FLOW,
    COLD_WATER_TEMPERATURE,
    HEAT_ENERGY,
    HEAT_POWER,

    // Hot Water Supply (GVS) Parameters
    GVS_TEMP_SUPPLY, // Температура теплоносителя (горячей воды) в подающем трубопроводе (То(гв))
    GVS_TEMP_RETURN, // Температура теплоносителя (горячей воды) в обратном трубопроводе (Тцо(цгв))
    GVS_PRESSURE_SUPPLY, // Давление теплоносителя (горячей воды) в подающем трубопроводе (Ро (гв))
    GVS_PRESSURE_RETURN, // Давление теплоносителя (горячей воды) в обратном трубопроводе (Рцо (цгв))
    GVS_MASS_FLOW_SUPPLY, // Массовый расход теплоносителя (горячей воды) в подающем трубопроводе (ΔG о (гв))
    GVS_MASS_FLOW_RETURN, // Массовый расход теплоносителя (горячей воды) в обратном трубопроводе (ΔG цо (цгв))
    GVS_VOLUME_FLOW_SUPPLY, // Объемный расход теплоносителя (горячей воды) в подающем трубопроводе (ΔVо(гв))
    GVS_VOLUME_FLOW_RETURN, // Объемный расход теплоносителя (горячей воды) в обратном трубопроводе (ΔVцо (цгв))
    GVS_TOTAL_MASS_SUPPLY, // Масса теплоносителя (горячей воды) в подающем трубопроводе нарастающим итогом (Gо (гв))
    GVS_TOTAL_MASS_RETURN, // Масса теплоносителя (горячей воды) в обратном трубопроводе нарастающим итогом (Gцо (цгв))
    GVS_HEAT_ENERGY_TOTAL, // Суммарное количество тепла на нужды горячего водоснабжения (Qгвс)

    // Cold Water Supply (HVS) Parameters
    HVS_TEMPERATURE, // Температура холодной воды (T хв)
    HVS_PRESSURE, // Давление холодной воды (Рхв)
    HVS_MASS_FLOW, // Массовый расход холодной воды (для ГВС) (ΔG хв)
    HVS_VOLUME_FLOW, // Объемный расход холодной воды (ΔVхв)

    // Heat / Central Heating (ЦО) / Ventilation Heating (Вент) Parameters
    HEAT_ENERGY_ACCUMULATED, // Тепловая энергия на тепловом вводе нарастающим итогом (Qо)
    HEAT_METER_OPERATING_TIME_ACCUMULATED, // Время наработки теплосчетчика на тепловом вводе нарастающим итогом (Тнар.)
    HEAT_METER_ERROR_TIME_ACCUMULATED, // Время работы прибора учета с ошибками (Тош)
    HEAT_MAKEUP_VOLUME_FLOW, // Объемный расход теплоносителя на подпитку отопления (ΔYпп о)
    HEAT_TEMP_SUPPLY, // Температура теплоносителя в подающем трубопроводе для ЦО/Вент (similar to T1 in appendix)
    HEAT_TEMP_RETURN, // Температура теплоносителя в обратном трубопроводе для ЦО/Вент (similar to T2 in appendix)
    HEAT_PRESSURE_SUPPLY, // Давление теплоносителя в подающем трубопроводе для ЦО/Вент (similar to P1 in appendix)
    HEAT_PRESSURE_RETURN, // Давление теплоносителя в обратном трубопроводе для ЦО/Вент (similar to P2 in appendix)
    HEAT_VOLUME_FLOW_SUPPLY, // Объем теплоносителя, отпущенного по подающему трубопроводу для ЦО/Вент (similar to Q1 in appendix)
    HEAT_VOLUME_FLOW_RETURN, // Объем теплоносителя, отпущенного по обратному трубопроводу для ЦО/Вент (similar to Q2 in appendix)
    HEAT_MASS_FLOW_SUPPLY, // Масса теплоносителя, отпущенного по подающему трубопроводу для ЦО/Вент (similar to M1 in appendix)
    HEAT_MASS_FLOW_RETURN, // Масса теплоносителя, отпущенного по обратному трубопроводу для ЦО/Вент (similar to M2 in appendix)
    HEAT_DIFFERENTIAL_PRESSURE, // Разница давления в подающем и обратном трубопроводе (dP)
    METER_CURRENT_ERRORS, // Текущие ошибки прибора учета (ER)
    METER_STATUS_FLAG, // Флаг состояния ПУ (boolean flag from Table 2)

    // Generic types (if specific mapping is not available or desired)
    GENERIC_FLOW,
    GENERIC_TEMPERATURE,
    GENERIC_ENERGY,
    GENERIC_POWER,
    UNKNOWN // For unidentifiable node IDs
}