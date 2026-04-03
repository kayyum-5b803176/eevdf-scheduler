package com.eevdf.scheduler.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.eevdf.scheduler.model.Task;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class TaskDao_Impl implements TaskDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<Task> __insertionAdapterOfTask;

  private final EntityDeletionOrUpdateAdapter<Task> __deletionAdapterOfTask;

  private final EntityDeletionOrUpdateAdapter<Task> __updateAdapterOfTask;

  private final SharedSQLiteStatement __preparedStmtOfClearCompleted;

  private final SharedSQLiteStatement __preparedStmtOfStopAllRunning;

  public TaskDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfTask = new EntityInsertionAdapter<Task>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `tasks` (`id`,`name`,`description`,`priority`,`timeSliceSeconds`,`category`,`color`,`vruntime`,`eligibleTime`,`virtualDeadline`,`lag`,`remainingSeconds`,`isRunning`,`isCompleted`,`totalRunTime`,`runCount`,`createdAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Task entity) {
        if (entity.getId() == null) {
          statement.bindNull(1);
        } else {
          statement.bindString(1, entity.getId());
        }
        if (entity.getName() == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.getName());
        }
        if (entity.getDescription() == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.getDescription());
        }
        statement.bindLong(4, entity.getPriority());
        statement.bindLong(5, entity.getTimeSliceSeconds());
        if (entity.getCategory() == null) {
          statement.bindNull(6);
        } else {
          statement.bindString(6, entity.getCategory());
        }
        statement.bindLong(7, entity.getColor());
        statement.bindDouble(8, entity.getVruntime());
        statement.bindDouble(9, entity.getEligibleTime());
        statement.bindDouble(10, entity.getVirtualDeadline());
        statement.bindDouble(11, entity.getLag());
        statement.bindLong(12, entity.getRemainingSeconds());
        final int _tmp = entity.isRunning() ? 1 : 0;
        statement.bindLong(13, _tmp);
        final int _tmp_1 = entity.isCompleted() ? 1 : 0;
        statement.bindLong(14, _tmp_1);
        statement.bindLong(15, entity.getTotalRunTime());
        statement.bindLong(16, entity.getRunCount());
        statement.bindLong(17, entity.getCreatedAt());
      }
    };
    this.__deletionAdapterOfTask = new EntityDeletionOrUpdateAdapter<Task>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `tasks` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Task entity) {
        if (entity.getId() == null) {
          statement.bindNull(1);
        } else {
          statement.bindString(1, entity.getId());
        }
      }
    };
    this.__updateAdapterOfTask = new EntityDeletionOrUpdateAdapter<Task>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `tasks` SET `id` = ?,`name` = ?,`description` = ?,`priority` = ?,`timeSliceSeconds` = ?,`category` = ?,`color` = ?,`vruntime` = ?,`eligibleTime` = ?,`virtualDeadline` = ?,`lag` = ?,`remainingSeconds` = ?,`isRunning` = ?,`isCompleted` = ?,`totalRunTime` = ?,`runCount` = ?,`createdAt` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Task entity) {
        if (entity.getId() == null) {
          statement.bindNull(1);
        } else {
          statement.bindString(1, entity.getId());
        }
        if (entity.getName() == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.getName());
        }
        if (entity.getDescription() == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.getDescription());
        }
        statement.bindLong(4, entity.getPriority());
        statement.bindLong(5, entity.getTimeSliceSeconds());
        if (entity.getCategory() == null) {
          statement.bindNull(6);
        } else {
          statement.bindString(6, entity.getCategory());
        }
        statement.bindLong(7, entity.getColor());
        statement.bindDouble(8, entity.getVruntime());
        statement.bindDouble(9, entity.getEligibleTime());
        statement.bindDouble(10, entity.getVirtualDeadline());
        statement.bindDouble(11, entity.getLag());
        statement.bindLong(12, entity.getRemainingSeconds());
        final int _tmp = entity.isRunning() ? 1 : 0;
        statement.bindLong(13, _tmp);
        final int _tmp_1 = entity.isCompleted() ? 1 : 0;
        statement.bindLong(14, _tmp_1);
        statement.bindLong(15, entity.getTotalRunTime());
        statement.bindLong(16, entity.getRunCount());
        statement.bindLong(17, entity.getCreatedAt());
        if (entity.getId() == null) {
          statement.bindNull(18);
        } else {
          statement.bindString(18, entity.getId());
        }
      }
    };
    this.__preparedStmtOfClearCompleted = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM tasks WHERE isCompleted = 1";
        return _query;
      }
    };
    this.__preparedStmtOfStopAllRunning = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE tasks SET isRunning = 0";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final Task task, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfTask.insert(task);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final Task task, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __deletionAdapterOfTask.handle(task);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final Task task, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfTask.handle(task);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object clearCompleted(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearCompleted.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfClearCompleted.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object stopAllRunning(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfStopAllRunning.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfStopAllRunning.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public LiveData<List<Task>> getAllTasks() {
    final String _sql = "SELECT * FROM tasks ORDER BY virtualDeadline ASC, priority DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return __db.getInvalidationTracker().createLiveData(new String[] {"tasks"}, false, new Callable<List<Task>>() {
      @Override
      @Nullable
      public List<Task> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfDescription = CursorUtil.getColumnIndexOrThrow(_cursor, "description");
          final int _cursorIndexOfPriority = CursorUtil.getColumnIndexOrThrow(_cursor, "priority");
          final int _cursorIndexOfTimeSliceSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "timeSliceSeconds");
          final int _cursorIndexOfCategory = CursorUtil.getColumnIndexOrThrow(_cursor, "category");
          final int _cursorIndexOfColor = CursorUtil.getColumnIndexOrThrow(_cursor, "color");
          final int _cursorIndexOfVruntime = CursorUtil.getColumnIndexOrThrow(_cursor, "vruntime");
          final int _cursorIndexOfEligibleTime = CursorUtil.getColumnIndexOrThrow(_cursor, "eligibleTime");
          final int _cursorIndexOfVirtualDeadline = CursorUtil.getColumnIndexOrThrow(_cursor, "virtualDeadline");
          final int _cursorIndexOfLag = CursorUtil.getColumnIndexOrThrow(_cursor, "lag");
          final int _cursorIndexOfRemainingSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "remainingSeconds");
          final int _cursorIndexOfIsRunning = CursorUtil.getColumnIndexOrThrow(_cursor, "isRunning");
          final int _cursorIndexOfIsCompleted = CursorUtil.getColumnIndexOrThrow(_cursor, "isCompleted");
          final int _cursorIndexOfTotalRunTime = CursorUtil.getColumnIndexOrThrow(_cursor, "totalRunTime");
          final int _cursorIndexOfRunCount = CursorUtil.getColumnIndexOrThrow(_cursor, "runCount");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final List<Task> _result = new ArrayList<Task>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Task _item;
            final String _tmpId;
            if (_cursor.isNull(_cursorIndexOfId)) {
              _tmpId = null;
            } else {
              _tmpId = _cursor.getString(_cursorIndexOfId);
            }
            final String _tmpName;
            if (_cursor.isNull(_cursorIndexOfName)) {
              _tmpName = null;
            } else {
              _tmpName = _cursor.getString(_cursorIndexOfName);
            }
            final String _tmpDescription;
            if (_cursor.isNull(_cursorIndexOfDescription)) {
              _tmpDescription = null;
            } else {
              _tmpDescription = _cursor.getString(_cursorIndexOfDescription);
            }
            final int _tmpPriority;
            _tmpPriority = _cursor.getInt(_cursorIndexOfPriority);
            final long _tmpTimeSliceSeconds;
            _tmpTimeSliceSeconds = _cursor.getLong(_cursorIndexOfTimeSliceSeconds);
            final String _tmpCategory;
            if (_cursor.isNull(_cursorIndexOfCategory)) {
              _tmpCategory = null;
            } else {
              _tmpCategory = _cursor.getString(_cursorIndexOfCategory);
            }
            final int _tmpColor;
            _tmpColor = _cursor.getInt(_cursorIndexOfColor);
            final double _tmpVruntime;
            _tmpVruntime = _cursor.getDouble(_cursorIndexOfVruntime);
            final double _tmpEligibleTime;
            _tmpEligibleTime = _cursor.getDouble(_cursorIndexOfEligibleTime);
            final double _tmpVirtualDeadline;
            _tmpVirtualDeadline = _cursor.getDouble(_cursorIndexOfVirtualDeadline);
            final double _tmpLag;
            _tmpLag = _cursor.getDouble(_cursorIndexOfLag);
            final long _tmpRemainingSeconds;
            _tmpRemainingSeconds = _cursor.getLong(_cursorIndexOfRemainingSeconds);
            final boolean _tmpIsRunning;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsRunning);
            _tmpIsRunning = _tmp != 0;
            final boolean _tmpIsCompleted;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIsCompleted);
            _tmpIsCompleted = _tmp_1 != 0;
            final long _tmpTotalRunTime;
            _tmpTotalRunTime = _cursor.getLong(_cursorIndexOfTotalRunTime);
            final int _tmpRunCount;
            _tmpRunCount = _cursor.getInt(_cursorIndexOfRunCount);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _item = new Task(_tmpId,_tmpName,_tmpDescription,_tmpPriority,_tmpTimeSliceSeconds,_tmpCategory,_tmpColor,_tmpVruntime,_tmpEligibleTime,_tmpVirtualDeadline,_tmpLag,_tmpRemainingSeconds,_tmpIsRunning,_tmpIsCompleted,_tmpTotalRunTime,_tmpRunCount,_tmpCreatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public LiveData<List<Task>> getActiveTasks() {
    final String _sql = "SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY virtualDeadline ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return __db.getInvalidationTracker().createLiveData(new String[] {"tasks"}, false, new Callable<List<Task>>() {
      @Override
      @Nullable
      public List<Task> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfDescription = CursorUtil.getColumnIndexOrThrow(_cursor, "description");
          final int _cursorIndexOfPriority = CursorUtil.getColumnIndexOrThrow(_cursor, "priority");
          final int _cursorIndexOfTimeSliceSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "timeSliceSeconds");
          final int _cursorIndexOfCategory = CursorUtil.getColumnIndexOrThrow(_cursor, "category");
          final int _cursorIndexOfColor = CursorUtil.getColumnIndexOrThrow(_cursor, "color");
          final int _cursorIndexOfVruntime = CursorUtil.getColumnIndexOrThrow(_cursor, "vruntime");
          final int _cursorIndexOfEligibleTime = CursorUtil.getColumnIndexOrThrow(_cursor, "eligibleTime");
          final int _cursorIndexOfVirtualDeadline = CursorUtil.getColumnIndexOrThrow(_cursor, "virtualDeadline");
          final int _cursorIndexOfLag = CursorUtil.getColumnIndexOrThrow(_cursor, "lag");
          final int _cursorIndexOfRemainingSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "remainingSeconds");
          final int _cursorIndexOfIsRunning = CursorUtil.getColumnIndexOrThrow(_cursor, "isRunning");
          final int _cursorIndexOfIsCompleted = CursorUtil.getColumnIndexOrThrow(_cursor, "isCompleted");
          final int _cursorIndexOfTotalRunTime = CursorUtil.getColumnIndexOrThrow(_cursor, "totalRunTime");
          final int _cursorIndexOfRunCount = CursorUtil.getColumnIndexOrThrow(_cursor, "runCount");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final List<Task> _result = new ArrayList<Task>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Task _item;
            final String _tmpId;
            if (_cursor.isNull(_cursorIndexOfId)) {
              _tmpId = null;
            } else {
              _tmpId = _cursor.getString(_cursorIndexOfId);
            }
            final String _tmpName;
            if (_cursor.isNull(_cursorIndexOfName)) {
              _tmpName = null;
            } else {
              _tmpName = _cursor.getString(_cursorIndexOfName);
            }
            final String _tmpDescription;
            if (_cursor.isNull(_cursorIndexOfDescription)) {
              _tmpDescription = null;
            } else {
              _tmpDescription = _cursor.getString(_cursorIndexOfDescription);
            }
            final int _tmpPriority;
            _tmpPriority = _cursor.getInt(_cursorIndexOfPriority);
            final long _tmpTimeSliceSeconds;
            _tmpTimeSliceSeconds = _cursor.getLong(_cursorIndexOfTimeSliceSeconds);
            final String _tmpCategory;
            if (_cursor.isNull(_cursorIndexOfCategory)) {
              _tmpCategory = null;
            } else {
              _tmpCategory = _cursor.getString(_cursorIndexOfCategory);
            }
            final int _tmpColor;
            _tmpColor = _cursor.getInt(_cursorIndexOfColor);
            final double _tmpVruntime;
            _tmpVruntime = _cursor.getDouble(_cursorIndexOfVruntime);
            final double _tmpEligibleTime;
            _tmpEligibleTime = _cursor.getDouble(_cursorIndexOfEligibleTime);
            final double _tmpVirtualDeadline;
            _tmpVirtualDeadline = _cursor.getDouble(_cursorIndexOfVirtualDeadline);
            final double _tmpLag;
            _tmpLag = _cursor.getDouble(_cursorIndexOfLag);
            final long _tmpRemainingSeconds;
            _tmpRemainingSeconds = _cursor.getLong(_cursorIndexOfRemainingSeconds);
            final boolean _tmpIsRunning;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsRunning);
            _tmpIsRunning = _tmp != 0;
            final boolean _tmpIsCompleted;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIsCompleted);
            _tmpIsCompleted = _tmp_1 != 0;
            final long _tmpTotalRunTime;
            _tmpTotalRunTime = _cursor.getLong(_cursorIndexOfTotalRunTime);
            final int _tmpRunCount;
            _tmpRunCount = _cursor.getInt(_cursorIndexOfRunCount);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _item = new Task(_tmpId,_tmpName,_tmpDescription,_tmpPriority,_tmpTimeSliceSeconds,_tmpCategory,_tmpColor,_tmpVruntime,_tmpEligibleTime,_tmpVirtualDeadline,_tmpLag,_tmpRemainingSeconds,_tmpIsRunning,_tmpIsCompleted,_tmpTotalRunTime,_tmpRunCount,_tmpCreatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public LiveData<List<Task>> getCompletedTasks() {
    final String _sql = "SELECT * FROM tasks WHERE isCompleted = 1 ORDER BY createdAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return __db.getInvalidationTracker().createLiveData(new String[] {"tasks"}, false, new Callable<List<Task>>() {
      @Override
      @Nullable
      public List<Task> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfDescription = CursorUtil.getColumnIndexOrThrow(_cursor, "description");
          final int _cursorIndexOfPriority = CursorUtil.getColumnIndexOrThrow(_cursor, "priority");
          final int _cursorIndexOfTimeSliceSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "timeSliceSeconds");
          final int _cursorIndexOfCategory = CursorUtil.getColumnIndexOrThrow(_cursor, "category");
          final int _cursorIndexOfColor = CursorUtil.getColumnIndexOrThrow(_cursor, "color");
          final int _cursorIndexOfVruntime = CursorUtil.getColumnIndexOrThrow(_cursor, "vruntime");
          final int _cursorIndexOfEligibleTime = CursorUtil.getColumnIndexOrThrow(_cursor, "eligibleTime");
          final int _cursorIndexOfVirtualDeadline = CursorUtil.getColumnIndexOrThrow(_cursor, "virtualDeadline");
          final int _cursorIndexOfLag = CursorUtil.getColumnIndexOrThrow(_cursor, "lag");
          final int _cursorIndexOfRemainingSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "remainingSeconds");
          final int _cursorIndexOfIsRunning = CursorUtil.getColumnIndexOrThrow(_cursor, "isRunning");
          final int _cursorIndexOfIsCompleted = CursorUtil.getColumnIndexOrThrow(_cursor, "isCompleted");
          final int _cursorIndexOfTotalRunTime = CursorUtil.getColumnIndexOrThrow(_cursor, "totalRunTime");
          final int _cursorIndexOfRunCount = CursorUtil.getColumnIndexOrThrow(_cursor, "runCount");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final List<Task> _result = new ArrayList<Task>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Task _item;
            final String _tmpId;
            if (_cursor.isNull(_cursorIndexOfId)) {
              _tmpId = null;
            } else {
              _tmpId = _cursor.getString(_cursorIndexOfId);
            }
            final String _tmpName;
            if (_cursor.isNull(_cursorIndexOfName)) {
              _tmpName = null;
            } else {
              _tmpName = _cursor.getString(_cursorIndexOfName);
            }
            final String _tmpDescription;
            if (_cursor.isNull(_cursorIndexOfDescription)) {
              _tmpDescription = null;
            } else {
              _tmpDescription = _cursor.getString(_cursorIndexOfDescription);
            }
            final int _tmpPriority;
            _tmpPriority = _cursor.getInt(_cursorIndexOfPriority);
            final long _tmpTimeSliceSeconds;
            _tmpTimeSliceSeconds = _cursor.getLong(_cursorIndexOfTimeSliceSeconds);
            final String _tmpCategory;
            if (_cursor.isNull(_cursorIndexOfCategory)) {
              _tmpCategory = null;
            } else {
              _tmpCategory = _cursor.getString(_cursorIndexOfCategory);
            }
            final int _tmpColor;
            _tmpColor = _cursor.getInt(_cursorIndexOfColor);
            final double _tmpVruntime;
            _tmpVruntime = _cursor.getDouble(_cursorIndexOfVruntime);
            final double _tmpEligibleTime;
            _tmpEligibleTime = _cursor.getDouble(_cursorIndexOfEligibleTime);
            final double _tmpVirtualDeadline;
            _tmpVirtualDeadline = _cursor.getDouble(_cursorIndexOfVirtualDeadline);
            final double _tmpLag;
            _tmpLag = _cursor.getDouble(_cursorIndexOfLag);
            final long _tmpRemainingSeconds;
            _tmpRemainingSeconds = _cursor.getLong(_cursorIndexOfRemainingSeconds);
            final boolean _tmpIsRunning;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsRunning);
            _tmpIsRunning = _tmp != 0;
            final boolean _tmpIsCompleted;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIsCompleted);
            _tmpIsCompleted = _tmp_1 != 0;
            final long _tmpTotalRunTime;
            _tmpTotalRunTime = _cursor.getLong(_cursorIndexOfTotalRunTime);
            final int _tmpRunCount;
            _tmpRunCount = _cursor.getInt(_cursorIndexOfRunCount);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _item = new Task(_tmpId,_tmpName,_tmpDescription,_tmpPriority,_tmpTimeSliceSeconds,_tmpCategory,_tmpColor,_tmpVruntime,_tmpEligibleTime,_tmpVirtualDeadline,_tmpLag,_tmpRemainingSeconds,_tmpIsRunning,_tmpIsCompleted,_tmpTotalRunTime,_tmpRunCount,_tmpCreatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getTaskById(final String id, final Continuation<? super Task> $completion) {
    final String _sql = "SELECT * FROM tasks WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    if (id == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, id);
    }
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Task>() {
      @Override
      @Nullable
      public Task call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfDescription = CursorUtil.getColumnIndexOrThrow(_cursor, "description");
          final int _cursorIndexOfPriority = CursorUtil.getColumnIndexOrThrow(_cursor, "priority");
          final int _cursorIndexOfTimeSliceSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "timeSliceSeconds");
          final int _cursorIndexOfCategory = CursorUtil.getColumnIndexOrThrow(_cursor, "category");
          final int _cursorIndexOfColor = CursorUtil.getColumnIndexOrThrow(_cursor, "color");
          final int _cursorIndexOfVruntime = CursorUtil.getColumnIndexOrThrow(_cursor, "vruntime");
          final int _cursorIndexOfEligibleTime = CursorUtil.getColumnIndexOrThrow(_cursor, "eligibleTime");
          final int _cursorIndexOfVirtualDeadline = CursorUtil.getColumnIndexOrThrow(_cursor, "virtualDeadline");
          final int _cursorIndexOfLag = CursorUtil.getColumnIndexOrThrow(_cursor, "lag");
          final int _cursorIndexOfRemainingSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "remainingSeconds");
          final int _cursorIndexOfIsRunning = CursorUtil.getColumnIndexOrThrow(_cursor, "isRunning");
          final int _cursorIndexOfIsCompleted = CursorUtil.getColumnIndexOrThrow(_cursor, "isCompleted");
          final int _cursorIndexOfTotalRunTime = CursorUtil.getColumnIndexOrThrow(_cursor, "totalRunTime");
          final int _cursorIndexOfRunCount = CursorUtil.getColumnIndexOrThrow(_cursor, "runCount");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final Task _result;
          if (_cursor.moveToFirst()) {
            final String _tmpId;
            if (_cursor.isNull(_cursorIndexOfId)) {
              _tmpId = null;
            } else {
              _tmpId = _cursor.getString(_cursorIndexOfId);
            }
            final String _tmpName;
            if (_cursor.isNull(_cursorIndexOfName)) {
              _tmpName = null;
            } else {
              _tmpName = _cursor.getString(_cursorIndexOfName);
            }
            final String _tmpDescription;
            if (_cursor.isNull(_cursorIndexOfDescription)) {
              _tmpDescription = null;
            } else {
              _tmpDescription = _cursor.getString(_cursorIndexOfDescription);
            }
            final int _tmpPriority;
            _tmpPriority = _cursor.getInt(_cursorIndexOfPriority);
            final long _tmpTimeSliceSeconds;
            _tmpTimeSliceSeconds = _cursor.getLong(_cursorIndexOfTimeSliceSeconds);
            final String _tmpCategory;
            if (_cursor.isNull(_cursorIndexOfCategory)) {
              _tmpCategory = null;
            } else {
              _tmpCategory = _cursor.getString(_cursorIndexOfCategory);
            }
            final int _tmpColor;
            _tmpColor = _cursor.getInt(_cursorIndexOfColor);
            final double _tmpVruntime;
            _tmpVruntime = _cursor.getDouble(_cursorIndexOfVruntime);
            final double _tmpEligibleTime;
            _tmpEligibleTime = _cursor.getDouble(_cursorIndexOfEligibleTime);
            final double _tmpVirtualDeadline;
            _tmpVirtualDeadline = _cursor.getDouble(_cursorIndexOfVirtualDeadline);
            final double _tmpLag;
            _tmpLag = _cursor.getDouble(_cursorIndexOfLag);
            final long _tmpRemainingSeconds;
            _tmpRemainingSeconds = _cursor.getLong(_cursorIndexOfRemainingSeconds);
            final boolean _tmpIsRunning;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsRunning);
            _tmpIsRunning = _tmp != 0;
            final boolean _tmpIsCompleted;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIsCompleted);
            _tmpIsCompleted = _tmp_1 != 0;
            final long _tmpTotalRunTime;
            _tmpTotalRunTime = _cursor.getLong(_cursorIndexOfTotalRunTime);
            final int _tmpRunCount;
            _tmpRunCount = _cursor.getInt(_cursorIndexOfRunCount);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _result = new Task(_tmpId,_tmpName,_tmpDescription,_tmpPriority,_tmpTimeSliceSeconds,_tmpCategory,_tmpColor,_tmpVruntime,_tmpEligibleTime,_tmpVirtualDeadline,_tmpLag,_tmpRemainingSeconds,_tmpIsRunning,_tmpIsCompleted,_tmpTotalRunTime,_tmpRunCount,_tmpCreatedAt);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getActiveTasksSync(final Continuation<? super List<Task>> $completion) {
    final String _sql = "SELECT * FROM tasks WHERE isCompleted = 0";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<Task>>() {
      @Override
      @NonNull
      public List<Task> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfDescription = CursorUtil.getColumnIndexOrThrow(_cursor, "description");
          final int _cursorIndexOfPriority = CursorUtil.getColumnIndexOrThrow(_cursor, "priority");
          final int _cursorIndexOfTimeSliceSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "timeSliceSeconds");
          final int _cursorIndexOfCategory = CursorUtil.getColumnIndexOrThrow(_cursor, "category");
          final int _cursorIndexOfColor = CursorUtil.getColumnIndexOrThrow(_cursor, "color");
          final int _cursorIndexOfVruntime = CursorUtil.getColumnIndexOrThrow(_cursor, "vruntime");
          final int _cursorIndexOfEligibleTime = CursorUtil.getColumnIndexOrThrow(_cursor, "eligibleTime");
          final int _cursorIndexOfVirtualDeadline = CursorUtil.getColumnIndexOrThrow(_cursor, "virtualDeadline");
          final int _cursorIndexOfLag = CursorUtil.getColumnIndexOrThrow(_cursor, "lag");
          final int _cursorIndexOfRemainingSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "remainingSeconds");
          final int _cursorIndexOfIsRunning = CursorUtil.getColumnIndexOrThrow(_cursor, "isRunning");
          final int _cursorIndexOfIsCompleted = CursorUtil.getColumnIndexOrThrow(_cursor, "isCompleted");
          final int _cursorIndexOfTotalRunTime = CursorUtil.getColumnIndexOrThrow(_cursor, "totalRunTime");
          final int _cursorIndexOfRunCount = CursorUtil.getColumnIndexOrThrow(_cursor, "runCount");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final List<Task> _result = new ArrayList<Task>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Task _item;
            final String _tmpId;
            if (_cursor.isNull(_cursorIndexOfId)) {
              _tmpId = null;
            } else {
              _tmpId = _cursor.getString(_cursorIndexOfId);
            }
            final String _tmpName;
            if (_cursor.isNull(_cursorIndexOfName)) {
              _tmpName = null;
            } else {
              _tmpName = _cursor.getString(_cursorIndexOfName);
            }
            final String _tmpDescription;
            if (_cursor.isNull(_cursorIndexOfDescription)) {
              _tmpDescription = null;
            } else {
              _tmpDescription = _cursor.getString(_cursorIndexOfDescription);
            }
            final int _tmpPriority;
            _tmpPriority = _cursor.getInt(_cursorIndexOfPriority);
            final long _tmpTimeSliceSeconds;
            _tmpTimeSliceSeconds = _cursor.getLong(_cursorIndexOfTimeSliceSeconds);
            final String _tmpCategory;
            if (_cursor.isNull(_cursorIndexOfCategory)) {
              _tmpCategory = null;
            } else {
              _tmpCategory = _cursor.getString(_cursorIndexOfCategory);
            }
            final int _tmpColor;
            _tmpColor = _cursor.getInt(_cursorIndexOfColor);
            final double _tmpVruntime;
            _tmpVruntime = _cursor.getDouble(_cursorIndexOfVruntime);
            final double _tmpEligibleTime;
            _tmpEligibleTime = _cursor.getDouble(_cursorIndexOfEligibleTime);
            final double _tmpVirtualDeadline;
            _tmpVirtualDeadline = _cursor.getDouble(_cursorIndexOfVirtualDeadline);
            final double _tmpLag;
            _tmpLag = _cursor.getDouble(_cursorIndexOfLag);
            final long _tmpRemainingSeconds;
            _tmpRemainingSeconds = _cursor.getLong(_cursorIndexOfRemainingSeconds);
            final boolean _tmpIsRunning;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsRunning);
            _tmpIsRunning = _tmp != 0;
            final boolean _tmpIsCompleted;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIsCompleted);
            _tmpIsCompleted = _tmp_1 != 0;
            final long _tmpTotalRunTime;
            _tmpTotalRunTime = _cursor.getLong(_cursorIndexOfTotalRunTime);
            final int _tmpRunCount;
            _tmpRunCount = _cursor.getInt(_cursorIndexOfRunCount);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _item = new Task(_tmpId,_tmpName,_tmpDescription,_tmpPriority,_tmpTimeSliceSeconds,_tmpCategory,_tmpColor,_tmpVruntime,_tmpEligibleTime,_tmpVirtualDeadline,_tmpLag,_tmpRemainingSeconds,_tmpIsRunning,_tmpIsCompleted,_tmpTotalRunTime,_tmpRunCount,_tmpCreatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
