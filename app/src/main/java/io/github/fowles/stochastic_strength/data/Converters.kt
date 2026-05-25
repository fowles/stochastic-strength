package io.github.fowles.stochastic_strength.data

import androidx.room.TypeConverter
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel

class Converters {
    @TypeConverter fun fromMuscleGroup(v: MuscleGroup): String = v.name
    @TypeConverter fun toMuscleGroup(v: String): MuscleGroup = MuscleGroup.valueOf(v)

    @TypeConverter fun fromEquipment(v: Equipment): String = v.name
    @TypeConverter fun toEquipment(v: String): Equipment = Equipment.valueOf(v)

    @TypeConverter fun fromSetFeedback(v: SetFeedback?): String? = v?.name
    @TypeConverter fun toSetFeedback(v: String?): SetFeedback? = v?.let { SetFeedback.valueOf(it) }

    @TypeConverter fun fromSex(v: Sex): String = v.name
    @TypeConverter fun toSex(v: String): Sex = Sex.valueOf(v)

    @TypeConverter fun fromStrengthLevel(v: StrengthLevel): String = v.name
    @TypeConverter fun toStrengthLevel(v: String): StrengthLevel = StrengthLevel.valueOf(v)

    @TypeConverter
    fun fromMuscleGroupList(v: List<MuscleGroup>): String = v.joinToString(",") { it.name }

    @TypeConverter
    fun toMuscleGroupList(v: String): List<MuscleGroup> =
        if (v.isEmpty()) emptyList() else v.split(",").map { MuscleGroup.valueOf(it) }
}
