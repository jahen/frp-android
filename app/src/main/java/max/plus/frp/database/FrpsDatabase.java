package max.plus.frp.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Config.class}, version = 4, exportSchema = false)
public abstract class FrpsDatabase extends RoomDatabase {
    private static volatile FrpsDatabase instance;

    public abstract ConfigDao configDao();

    public static FrpsDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (FrpsDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context,
                            FrpsDatabase.class, "frps_android.db")
                            .addMigrations(new Migration(2, 3) {
                                @Override
                                public void migrate(@NonNull SupportSQLiteDatabase database) {
                                    database.execSQL("ALTER TABLE config ADD COLUMN format TEXT DEFAULT 'ini'");
                                }
                            }, new Migration(3, 4) {
                                @Override
                                public void migrate(@NonNull SupportSQLiteDatabase database) {
                                    database.execSQL("ALTER TABLE config ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0");
                                }
                            })
                            .build();
                }
            }
        }
        return instance;
    }
}