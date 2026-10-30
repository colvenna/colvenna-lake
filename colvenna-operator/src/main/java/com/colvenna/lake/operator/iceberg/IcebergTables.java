package com.colvenna.lake.operator.iceberg;

import com.colvenna.lake.operator.lake.LakeReader;
import java.util.Optional;

/** What a publication reads of a Paimon table: its latest snapshot and its writers' metadata. */
public interface IcebergTables {
    /** The latest snapshot and when it was committed. */
    record Head(long snapshot, long committedMillis) {}

    Optional<Head> head(String database, String table) throws Exception;

    Optional<LakeReader.IcebergMetadata> metadata(String database, String table, long snapshot)
            throws Exception;

    /** The files a snapshot's metadata holds, and those it is to hold. */
    LakeReader.IcebergFiles files(String database, String table, long snapshot) throws Exception;

    /** The tables through the operator's own catalog identity and data tokens. */
    static IcebergTables of(LakeReader lake) {
        return new IcebergTables() {
            @Override
            public Optional<Head> head(String database, String table) throws Exception {
                return lake.head(database, table)
                        .map(h -> new Head(h.snapshotId(), h.commitMillis()));
            }

            @Override
            public Optional<LakeReader.IcebergMetadata> metadata(
                    String database, String table, long snapshot) throws Exception {
                return lake.icebergMetadata(database, table, snapshot);
            }

            @Override
            public LakeReader.IcebergFiles files(String database, String table, long snapshot)
                    throws Exception {
                return lake.icebergFiles(database, table, snapshot);
            }
        };
    }
}
