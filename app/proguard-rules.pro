# Retain source positions for retracing with this release's mapping.txt.
-keepattributes SourceFile,LineNumberTable
# Use a uniform source label; mapping.txt restores the original source locations.
-renamesourcefileattribute SourceFile

# Audited against cached AGP 9.1.1 and the locked runtime dependencies:
# - WorkManager 2.10.1 keeps ListenableWorker subclass names and constructors.
#   This covers DownloadWorker's persisted name; its custom Factory calls the
#   three-argument constructor directly. Do not duplicate these consumer rules.
# - Room 2.6.1 keeps RoomDatabase subclass names, but its memberless rule does
#   not retain constructors in R8 strict full mode. Room reflectively calls
#   WorkDatabase_Impl.getDeclaredConstructor().newInstance() during WorkManager
#   initialization; usage.txt from the failing release lists public <init>() as
#   removed. Keep only that constructor so scheduling can initialize the DB.
#   Nezzel's JobStore uses SQLiteOpenHelper and explicit JSONObject fields.
-keepclassmembers class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
# - AGP's optimize defaults preserve enum values()/valueOf(String). Persisted
#   JobStage, Preset, AudioConversion, DownloadError and saved MediaFilter use
#   explicit name/valueOf calls, not reflected fields; no enum keep-all needed.
# - Saved state contains scalars/string lists; intents use framework Uri and
#   ClipData parcelables. No app Parcelable/Serializable models or reflective
#   ViewModel constructors; AGP already preserves Parcelable CREATOR fields.
# - Compose needs no additional keeps. Engine dependency audit: see
#   engine/consumer-rules.pro.
