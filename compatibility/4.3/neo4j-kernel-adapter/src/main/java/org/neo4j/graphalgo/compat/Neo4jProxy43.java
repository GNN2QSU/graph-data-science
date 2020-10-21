/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.compat;

import org.apache.commons.io.output.WriterOutputStream;
import org.eclipse.collections.api.factory.Sets;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.ExternalSettings;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.internal.batchimport.AdditionalInitialIds;
import org.neo4j.internal.batchimport.BatchImporter;
import org.neo4j.internal.batchimport.BatchImporterFactory;
import org.neo4j.internal.batchimport.Configuration;
import org.neo4j.internal.batchimport.ImportLogic;
import org.neo4j.internal.batchimport.InputIterable;
import org.neo4j.internal.batchimport.cache.LongArray;
import org.neo4j.internal.batchimport.cache.NumberArrayFactory;
import org.neo4j.internal.batchimport.cache.OffHeapLongArray;
import org.neo4j.internal.batchimport.input.Collector;
import org.neo4j.internal.batchimport.input.IdType;
import org.neo4j.internal.batchimport.input.Input;
import org.neo4j.internal.batchimport.input.PropertySizeCalculator;
import org.neo4j.internal.batchimport.input.ReadableGroups;
import org.neo4j.internal.batchimport.staging.ExecutionMonitor;
import org.neo4j.internal.kernel.api.CursorFactory;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.internal.kernel.api.IndexQueryConstraints;
import org.neo4j.internal.kernel.api.IndexReadSession;
import org.neo4j.internal.kernel.api.NodeCursor;
import org.neo4j.internal.kernel.api.NodeLabelIndexCursor;
import org.neo4j.internal.kernel.api.NodeValueIndexCursor;
import org.neo4j.internal.kernel.api.PropertyCursor;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.RelationshipScanCursor;
import org.neo4j.internal.kernel.api.security.AccessMode;
import org.neo4j.internal.schema.IndexOrder;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.query.ExecutingQuery;
import org.neo4j.kernel.impl.api.security.RestrictedAccessMode;
import org.neo4j.kernel.impl.store.NodeLabelsField;
import org.neo4j.kernel.impl.store.NodeStore;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.format.RecordFormat;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;
import org.neo4j.kernel.impl.store.record.NodeRecord;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.kernel.impl.transaction.log.files.TransactionLogInitializer;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.logging.Level;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogTimeZone;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.logging.internal.LogService;
import org.neo4j.logging.internal.SimpleLogService;
import org.neo4j.logging.log4j.Log4jLogProvider;
import org.neo4j.logging.log4j.LogConfig;
import org.neo4j.memory.EmptyMemoryTracker;
import org.neo4j.memory.LocalMemoryTracker;
import org.neo4j.memory.MemoryPools;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.scheduler.JobScheduler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;

public final class Neo4jProxy43 implements Neo4jProxyApi {

    private static final MethodHandle CREATE_LOG_BUILDER;

    static {
        // signature changed midway through drop04 but we need to keep it compatible with drop03 at least
        // for as long as Aura runs on drop03

        var expectedParameters = new Class<?>[]{
            FileSystemAbstraction.class,
            Path.class,
            Level.class
        };
        var methodName = "createBuilder";

        var lookup = MethodHandles.lookup();
        MethodHandle createBuilder;
        try {
            try {
                // drop03 / early drop04
                var signature = MethodType.methodType(
                    LogConfig.Builder.class,
                    Path.class,
                    Level.class
                );
                var oldHandle = lookup.findStatic(LogConfig.class, methodName, signature);
                // we call the handle with an additional fs parameter in it's first position
                // on older versions that do not support that parameter, we need to drop it
                // before calling the actual method
                createBuilder = MethodHandles.dropArguments(oldHandle, 0, FileSystemAbstraction.class);
            } catch (NoSuchMethodException e) {
                // late drop04; weirdness in the methodType API prevents us from using `expectedParameters` here :/
                var signature = MethodType.methodType(
                    LogConfig.Builder.class,
                    /* added parameter in drop04 */ FileSystemAbstraction.class,
                    Path.class,
                    Level.class
                );
                // already correct signature
                createBuilder = lookup.findStatic(LogConfig.class, methodName, signature);
            }
        } catch (IllegalAccessException | NoSuchMethodException e) {
            // instead of failing at class initialization (which would fail the whole db)
            // we fail only when the method is accessed (which may be never)
            // so that we can still run every other operation.
            // At the time of this writing, only graph export with enableDebugLog will execute this code.
            var error = new LinkageError(
                formatWithLocale(
                    "Could not find suitable method `%s` on %s. Expected a static method with parameters %s.",
                    methodName,
                    LogConfig.class,
                    Arrays.toString(expectedParameters)
                ), e);
            var throwError = MethodHandles.throwException(LogConfig.Builder.class, LinkageError.class);
            throwError = throwError.bindTo(error);
            // Need to align the handle to the expected signature, so that can call it the same way as working handles
            createBuilder = MethodHandles.dropArguments(
                throwError,
                0,
                expectedParameters
            );
        }

        var methodType = createBuilder.type();
        if (methodType.returnType() != LogConfig.Builder.class) {
            failCreateBuilderHandle(
                "Expected the handle to return `%s`, but it returns `%s` instead.",
                LogConfig.Builder.class,
                methodType.returnType()
            );
        }
        if (methodType.parameterCount() != expectedParameters.length) {
            failCreateBuilderHandle(
                "Expected the handle to accept %d parameters, but it accepts `%d` instead.",
                expectedParameters.length,
                methodType.parameterCount()
            );
        }
        for (int i = 0; i < expectedParameters.length; i++) {
            Class<?> expectedParameter = expectedParameters[i];
            if (methodType.parameterType(i) != expectedParameter) {
                failCreateBuilderHandle(
                    "Expected the handle to accept `%s` at its parameter #%d, but it accepts `%s` instead.",
                    expectedParameter,
                    i,
                    methodType.parameterType(i)
                );
            }
        }
        CREATE_LOG_BUILDER = createBuilder;
    }

    private static void failCreateBuilderHandle(String message, Object... args) {
        throw new LinkageError(
            formatWithLocale(
                "Illegal method handle created. " +
                message +
                " This is a hard error, please file a bug report.",
                args
            )
        );
    }

    @Override
    public GdsGraphDatabaseAPI newDb(DatabaseManagementService dbms) {
        return new CompatGraphDatabaseAPI43(dbms);
    }

    @Override
    public AccessMode accessMode(CustomAccessMode customAccessMode) {
        return new CompatAccessMode43(customAccessMode);
    }

    @Override
    public AccessMode newRestrictedAccessMode(
        AccessMode original,
        AccessMode.Static restricting
    ) {
        return new RestrictedAccessMode(original, restricting);
    }

    @Override
    public <RECORD extends AbstractBaseRecord> void read(
        RecordFormat<RECORD> recordFormat,
        RECORD record,
        PageCursor cursor,
        RecordLoad mode,
        int recordSize,
        int recordsPerPage
    ) throws IOException {
        recordFormat.read(record, cursor, mode, recordSize, recordsPerPage);
    }

    @Override
    public long getHighestPossibleIdInUse(
        RecordStore<? extends AbstractBaseRecord> recordStore,
        PageCursorTracer pageCursorTracer
    ) {
        return recordStore.getHighestPossibleIdInUse(pageCursorTracer);
    }

    @Override
    public <RECORD extends AbstractBaseRecord> PageCursor openPageCursorForReading(
        RecordStore<RECORD> recordStore,
        long pageId,
        PageCursorTracer pageCursorTracer
    ) {
        return recordStore.openPageCursorForReading(pageId, pageCursorTracer);
    }

    @Override
    public PageCursor pageFileIO(
        PagedFile pagedFile,
        long pageId,
        int pageFileFlags,
        PageCursorTracer pageCursorTracer
    ) throws IOException {
        return pagedFile.io(pageId, pageFileFlags, pageCursorTracer);
    }

    @Override
    public PagedFile pageCacheMap(
        PageCache pageCache,
        File file,
        int pageSize,
        OpenOption... openOptions
    ) throws IOException {
        return pageCache.map(file.toPath(), pageSize, Sets.immutable.of(openOptions));
    }

    @Override
    public Path pagedFile(PagedFile pagedFile) {
        return pagedFile.path();
    }

    @Override
    public PropertyCursor allocatePropertyCursor(CursorFactory cursorFactory, PageCursorTracer cursorTracer, MemoryTracker memoryTracker) {
        return cursorFactory.allocatePropertyCursor(cursorTracer, memoryTracker);
    }

    @Override
    public NodeCursor allocateNodeCursor(CursorFactory cursorFactory, PageCursorTracer cursorTracer ) {
        return cursorFactory.allocateNodeCursor(cursorTracer);
    }

    @Override
    public RelationshipScanCursor allocateRelationshipScanCursor(CursorFactory cursorFactory, PageCursorTracer cursorTracer ) {
        return cursorFactory.allocateRelationshipScanCursor(cursorTracer);
    }

    @Override
    public NodeLabelIndexCursor allocateNodeLabelIndexCursor(CursorFactory cursorFactory, PageCursorTracer cursorTracer ) {
        return cursorFactory.allocateNodeLabelIndexCursor(cursorTracer);
    }

    @Override
    public NodeValueIndexCursor allocateNodeValueIndexCursor(
        CursorFactory cursorFactory,
        PageCursorTracer cursorTracer,
        MemoryTracker memoryTracker
    ) {
        return cursorFactory.allocateNodeValueIndexCursor(cursorTracer, memoryTracker);
    }

    @Override
    public long relationshipsReference(NodeCursor nodeCursor) {
        return nodeCursor.relationshipsReference();
    }

    @Override
    public long[] getNodeLabelFields(NodeRecord node, NodeStore nodeStore, PageCursorTracer cursorTracer) {
        return NodeLabelsField.get(node, nodeStore, cursorTracer);
    }

    @Override
    public void nodeLabelScan(Read dataRead, int label, NodeLabelIndexCursor cursor) {
        dataRead.nodeLabelScan(label, cursor, IndexOrder.NONE);
    }

    @Override
    public void nodeIndexScan(
        Read dataRead, IndexReadSession index, NodeValueIndexCursor cursor, IndexOrder indexOrder, boolean needsValues
    ) throws Exception {
        var indexQueryConstraints = indexOrder == IndexOrder.NONE
            ? IndexQueryConstraints.unordered(needsValues)
            : IndexQueryConstraints.constrained(indexOrder, needsValues);

        dataRead.nodeIndexScan(index, cursor, indexQueryConstraints);
    }

    @Override
    public void nodeIndexSeek(
        Read dataRead,
        IndexReadSession index,
        NodeValueIndexCursor cursor,
        IndexOrder indexOrder,
        boolean needsValues,
        IndexQuery query
    ) throws Exception {
        var indexQueryConstraints = indexOrder == IndexOrder.NONE
            ? IndexQueryConstraints.unordered(needsValues)
            : IndexQueryConstraints.constrained(indexOrder, needsValues);

        dataRead.nodeIndexSeek(index, cursor, indexQueryConstraints, query);
    }

    @Override
    public CompositeNodeCursor compositeNodeCursor(List<NodeLabelIndexCursor> cursors, int[] labelIds) {
        return new CompositeNodeCursor43(cursors, labelIds);
    }

    @Override
    public OffHeapLongArray newOffHeapLongArray(long length, long defaultValue, long base) {
        return new OffHeapLongArray(length, defaultValue, base, EmptyMemoryTracker.INSTANCE);
    }

    @Override
    public LongArray newChunkedLongArray(NumberArrayFactory numberArrayFactory, int size, long defaultValue) {
        return numberArrayFactory.newLongArray(size, defaultValue, EmptyMemoryTracker.INSTANCE);
    }

    @Override
    public MemoryTracker memoryTracker(KernelTransaction kernelTransaction) {
        return kernelTransaction.memoryTracker();
    }

    @Override
    public MemoryTracker emptyMemoryTracker() {
        return EmptyMemoryTracker.INSTANCE;
    }

    @Override
    public MemoryTracker limitedMemoryTracker(long limitInBytes, long grabSizeInBytes) {
        return new LocalMemoryTracker(MemoryPools.NO_TRACKING, limitInBytes, grabSizeInBytes, "setting");
    }

    @Override
    public MemoryTrackerProxy memoryTrackerProxy(MemoryTracker memoryTracker) {
        return MemoryTrackerProxy43.of(memoryTracker);
    }

    @Override
    public LogService logProviderForStoreAndRegister(
        Path storeLogPath,
        FileSystemAbstraction fs,
        LifeSupport lifeSupport
    ) {
        LogConfig.Builder builder;
        try {
            builder = (LogConfig.Builder) CREATE_LOG_BUILDER.invoke(fs, storeLogPath, Level.INFO);
        } catch (Throwable throwable) {
            // this is taken from ExceptionUtil, but don't have a dependency on that module here
            if (throwable instanceof Error) {
                throw (Error) throwable;
            }
            if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            }
            if (throwable instanceof IOException) {
                throw new UncheckedIOException((IOException) throwable);
            }
            throw new RuntimeException(throwable);
        }
        var neo4jLoggerContext = builder.build();
        var simpleLogService = new SimpleLogService(
            NullLogProvider.getInstance(),
            new Log4jLogProvider(neo4jLoggerContext)
        );
        return lifeSupport.add(simpleLogService);
    }

    @Override
    public Path metadataStore(DatabaseLayout databaseLayout) {
        return databaseLayout.metadataStore();
    }

    @Override
    public Path homeDirectory(DatabaseLayout databaseLayout) {
        return databaseLayout.getNeo4jLayout().homeDirectory();
    }

    @Override
    public BatchImporter instantiateBatchImporter(
        BatchImporterFactory factory,
        DatabaseLayout directoryStructure,
        FileSystemAbstraction fileSystem,
        PageCache externalPageCache,
        PageCacheTracer pageCacheTracer,
        Configuration config,
        LogService logService,
        ExecutionMonitor executionMonitor,
        AdditionalInitialIds additionalInitialIds,
        Config dbConfig,
        RecordFormats recordFormats,
        ImportLogic.Monitor monitor,
        JobScheduler jobScheduler,
        Collector badCollector
    ) {
        return factory.instantiate(
            directoryStructure,
            fileSystem,
            externalPageCache,
            pageCacheTracer,
            config,
            logService,
            executionMonitor,
            additionalInitialIds,
            dbConfig,
            recordFormats,
            monitor,
            jobScheduler,
            badCollector,
            TransactionLogInitializer.getLogFilesInitializer(),
            EmptyMemoryTracker.INSTANCE
        );
    }

    @Override
    public Input batchInputFrom(CompatInput compatInput) {
        return new InputFromCompatInput(compatInput);
    }

    @Override
    public String queryText(ExecutingQuery query) {
        return query.rawQueryText();
    }

    @Override
    public Log logger(
        Level level,
        ZoneId zoneId,
        DateTimeFormatter dateTimeFormatter,
        String category,
        PrintWriter writer
    ) {
        var outStream = new WriterOutputStream(writer, StandardCharsets.UTF_8);
        return this.logger(level, zoneId, dateTimeFormatter, category, outStream);
    }

    @Override
    public Log logger(
        Level level,
        ZoneId zoneId,
        DateTimeFormatter dateTimeFormatter,
        String category,
        OutputStream outputStream
    ) {
        var logTimeZone = Arrays
            .stream(LogTimeZone.values())
            .filter(tz -> tz.getZoneId().equals(zoneId))
            .findAny()
            .orElseThrow(() -> new IllegalArgumentException("Can only log in UTC or " + LogTimeZone.SYSTEM.getZoneId()));
        var context = LogConfig
            .createBuilder(outputStream, level)
            .withCategory(category != null)
            .withTimezone(logTimeZone)
            .build();

        return new Log4jLogProvider(context).getLog(category != null ? category : "");
    }

    @Override
    public Setting<Boolean> onlineBackupEnabled() {
        try {
            Class<?> onlineSettingsClass = Class.forName(
                "com.neo4j.configuration.OnlineBackupSettings");
            var onlineBackupEnabled = MethodHandles
                .lookup()
                .findStaticGetter(onlineSettingsClass, "online_backup_enabled", Setting.class)
                .invoke();
            //noinspection unchecked
            return (Setting<Boolean>) onlineBackupEnabled;
        } catch (Throwable e) {
            throw new IllegalStateException("The online_backup_enabled setting requires Neo4j Enterprise Edition to be available.");
        }
    }

    @Override
    public Setting<String> additionalJvm() {
        return ExternalSettings.additional_jvm;
    }

    @Override
    public Setting<Long> memoryTransactionMaxSize() {
        return GraphDatabaseSettings.memory_transaction_max_size;
    }

    private static final class InputFromCompatInput implements Input {
        private final CompatInput delegate;

        private InputFromCompatInput(CompatInput delegate) {
            this.delegate = delegate;
        }

        @Override
        public InputIterable nodes(Collector badCollector) {
            return delegate.nodes(badCollector);
        }

        @Override
        public InputIterable relationships(Collector badCollector) {
            return delegate.relationships(badCollector);
        }

        @Override
        public IdType idType() {
            return delegate.idType();
        }

        @Override
        public ReadableGroups groups() {
            return delegate.groups();
        }

        @Override
        public Estimates calculateEstimates(PropertySizeCalculator propertySizeCalculator) throws IOException {
            return delegate.calculateEstimates(propertySizeCalculator::calculateSize);
        }
    }
}