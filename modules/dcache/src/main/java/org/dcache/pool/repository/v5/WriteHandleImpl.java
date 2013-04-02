package org.dcache.pool.repository.v5;

import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

import diskCacheV111.util.CacheException;
import diskCacheV111.util.PnfsHandler;

import org.dcache.namespace.FileAttribute;
import org.dcache.pool.repository.Allocator;
import org.dcache.pool.repository.CacheEntry;
import org.dcache.pool.repository.EntryState;
import org.dcache.pool.repository.MetaDataRecord;
import org.dcache.pool.repository.ReplicaDescriptor;
import org.dcache.pool.repository.StickyRecord;
import org.dcache.util.Checksum;
import org.dcache.vehicles.FileAttributes;

import static com.google.common.collect.Iterables.concat;
import static java.util.Collections.singleton;

class WriteHandleImpl implements ReplicaDescriptor
{
    enum HandleState
    {
        OPEN, COMMITTED, CLOSED
    }

    private static Logger _log =
        LoggerFactory.getLogger("logger.org.dcache.repository");

    /**
     * Time that a new CACHED file with no sticky flags will be marked
     * sticky.
     */
    private static long HOLD_TIME = 5 * 60 * 1000; // 5 minutes

    private final CacheRepositoryV5 _repository;

    /** Space allocation is delegated to this allocator. */
    private final Allocator _allocator;

    /** The handler provides access to this entry. */
    private final MetaDataRecord _entry;

    /** Stub for talking to the PNFS manager. */
    private final PnfsHandler _pnfs;

    /** Sticky flags to be applied after the transfer. */
    private final List<StickyRecord> _stickyRecords;

    /** The entry state used during transfer. */
    private final EntryState _initialState;

    /** The entry state used when the handle is committed. */
    private EntryState _targetState;

    /** The state of the write handle. */
    private HandleState _state;

    /** Amount of space allocated for this handle. */
    private long _allocated;

    /** Current thread which performs allocation. */
    private Thread _allocationThread;

    WriteHandleImpl(CacheRepositoryV5 repository,
                    Allocator allocator,
                    PnfsHandler pnfs,
                    MetaDataRecord entry,
                    EntryState targetState,
                    List<StickyRecord> stickyRecords)
    {
        _repository = repository;
        _allocator = allocator;
        _pnfs = pnfs;
        _entry = entry;
        _initialState = entry.getState();
        _targetState = targetState;
        _stickyRecords = stickyRecords;
        _state = HandleState.OPEN;
        _allocated = 0;
    }

    private synchronized void setState(HandleState state)
    {
        _state = state;
        if (state != HandleState.OPEN && _allocationThread != null) {
            _allocationThread.interrupt();
        }
    }

    private synchronized boolean isOpen()
    {
        return _state == HandleState.OPEN;
    }

    /**
     * Sets the allocation thread to the calling thread. Blocks if
     * allocation thread is already set.
     *
     * @throws InterruptedException if thread is interrupted
     * @throws IllegalStateException if handle is closed
     */
    private synchronized void setAllocationThread()
        throws InterruptedException,
               IllegalStateException
    {
        while (_allocationThread != null) {
            wait();
        }

        if (!isOpen()) {
            throw new IllegalStateException("Handle is closed");
        }

        _allocationThread = Thread.currentThread();
    }

    /**
     * Clears the allocation thread field.
     */
    private synchronized void clearAllocationThread()
    {
        _allocationThread = null;
        notifyAll();
    }

    /**
     * Allocate space and block until space becomes available.
     *
     * @param size in bytes
     * @throws InterruptedException if thread is interrupted
     * @throws IllegalStateException if handle is closed
     * @throws IllegalArgumentException
     *             if <i>size</i> &lt; 0
     */
    @Override
    public void allocate(long size)
        throws IllegalStateException, IllegalArgumentException, InterruptedException
    {
        if (size < 0) {
            throw new IllegalArgumentException("Size is negative");
        }

        setAllocationThread();
        try {
            _allocator.allocate(size);
        } catch (InterruptedException e) {
            if (!isOpen()) {
                throw new IllegalStateException("Handle is closed");
            }
            throw e;
        } finally {
            clearAllocationThread();
        }

        synchronized (this) {
            _allocated += size;
            _entry.setSize(_allocated);
        }
    }

    /**
     * Freeing space through a write handle is not supported. This
     * method always throws IllegalStateException.
     */
    @Override
    public void free(long size)
        throws IllegalStateException
    {
        throw new IllegalStateException("Space cannot be freed through a write handle");
    }

    /**
     * Adjust space reservation. Will log an error in case of under
     * allocation.
     */
    private synchronized void adjustReservation(long length)
        throws InterruptedException
    {
        try {
            if (_allocated < length) {
                _log.error("Under allocation detected. This is a bug. Please report it.");
                _allocator.allocate(length - _allocated);
            } else if (_allocated > length) {
                _allocator.free(_allocated - length);
            }
            _allocated = length;
            _entry.setSize(length);
        } catch (InterruptedException e) {
            /* Space allocation is broken now. The entry size
             * matches up with what was actually allocated,
             * however the file on disk is too large.
             *
             * Should only happen during shutdown, so no harm done.
             */
            _log.warn("Failed to adjust space reservation because the operation was interrupted. The pool is now over allocated.");
            throw e;
        }
    }

    /**
     * Compare and update checksum. For now we only store the checksum
     * locally. TODO: Compare and update in PNFS.
     */
    private void storeChecksum(FileAttributes fileAttributes, Checksum checksum)
        throws CacheException
    {
        if (fileAttributes.isDefined(FileAttribute.CHECKSUM)) {
            for (Checksum existingChecksum : fileAttributes.getChecksums()) {
                if (checksum.getType() == existingChecksum.getType() && !checksum.equals(existingChecksum)) {
                    throw new CacheException(String.format("Checksum error: file=%s, expected=%s",
                            checksum, existingChecksum));
                }
            }
            fileAttributes.setChecksums(Sets.newHashSet(concat(fileAttributes
                    .getChecksums(), singleton(checksum))));
        } else {
            fileAttributes.setChecksums(Sets.newHashSet(checksum));
        }
        _entry.setFileAttributes(fileAttributes);
    }

    /**
     * Set file size in PNFS.
     *
     * If this is a new file, i.e. we did not get it from tape or
     * another pool, then update the size in the storage info and in
     * PNFS.  Otherwise fail the operation if the file size is wrong.
     */
    private void storeFileSize(FileAttributes fileAttributes, long length)
        throws CacheException
    {
        if (_initialState == EntryState.FROM_CLIENT &&
            (fileAttributes.isUndefined(FileAttribute.SIZE) || fileAttributes.getSize() == 0)) {
            fileAttributes.setSize(length);
            _entry.setFileAttributes(fileAttributes);
        } else if (fileAttributes.getSize() != length) {
            throw new CacheException("File does not have expected length");
        }
    }

    private void registerCacheLocation()
        throws CacheException
    {
        _pnfs.addCacheLocation(_entry.getPnfsId());
    }

    private void registerFileAttributes(FileAttributes attr)
            throws CacheException
    {
        _pnfs.setFileAttributes(_entry.getPnfsId(), attr);
    }

    private void setToTargetState()
    {
        /* In several situations, dCache requests a CACHED file
         * without having any sticky flags on it. Such files are
         * subject to immediate garbage collection if we are short on
         * disk space. Thus to give other clients time to access the
         * file, we mark it sticky for a short amount of time.
         */
        if (_targetState == EntryState.CACHED && _stickyRecords.isEmpty()) {
            long now = System.currentTimeMillis();
            _repository.setSticky(_entry, "self", now + HOLD_TIME, false);
        }

        /* Move entry to target state.
         */
        for (StickyRecord record: _stickyRecords) {
            _repository.setSticky(_entry, record.owner(), record.expire(), false);
        }
        _repository.setState(_entry, _targetState);
    }

    @Override
    public synchronized void commit(Checksum checksum)
        throws IllegalStateException, InterruptedException, CacheException
    {
        if (_state != HandleState.OPEN) {
            throw new IllegalStateException("Handle is closed");
        }

        try {
            _entry.touch();

            long length = getFile().length();
            adjustReservation(length);

            FileAttributes fileAttributes = _entry.getFileAttributes();
            storeFileSize(fileAttributes, length);
            if (checksum != null) {
                storeChecksum(fileAttributes, checksum);
            }

            FileAttributes attributesToUpdate = new FileAttributes();
            if(_initialState == EntryState.FROM_CLIENT) {
                attributesToUpdate.setAccessLatency(fileAttributes.getAccessLatency());
                attributesToUpdate.setRetentionPolicy(fileAttributes.getRetentionPolicy());
                attributesToUpdate.setSize(length);
            }
            attributesToUpdate.setLocations(singleton(_repository.getPoolName()));
            registerFileAttributes(attributesToUpdate);

            setToTargetState();

            setState(HandleState.COMMITTED);
        } catch (CacheException e) {
            /* If any of the PNFS operations return FILE_NOT_FOUND,
             * then we change the target state and the close method
             * will take care of removing the file.
             */
            if (e.getRc() == CacheException.FILE_NOT_FOUND) {
                _targetState = EntryState.REMOVED;
            }
            throw e;
        }
    }

    /**
     * Fails the operation. Called by close without a successful
     * commit. The file is either removed or marked bad, depending on
     * its state.
     */
    private synchronized void fail()
    {
        long length = getFile().length();
        try {
            adjustReservation(length);
        } catch (InterruptedException e) {
            // Carry on
        }

        /* Files from tape or from another pool are deleted in case of
         * errors.
         */
        if (_initialState == EntryState.FROM_POOL ||
            _initialState == EntryState.FROM_STORE) {
            _targetState = EntryState.REMOVED;
        }

        /* Register cache location unless replica is to be
         * removed.
         */
        if (_targetState != EntryState.REMOVED) {
            try {
                registerCacheLocation();
            } catch (CacheException e) {
                if (e.getRc() == CacheException.FILE_NOT_FOUND) {
                    _targetState = EntryState.REMOVED;
                }
            }
        }

        if (_targetState == EntryState.REMOVED) {
            _repository.setState(_entry, EntryState.REMOVED);
        } else {
            _log.warn("Marking pool entry as BROKEN");
            _repository.setState(_entry, EntryState.BROKEN);
        }
    }

    @Override
    public synchronized void close()
        throws IllegalStateException
    {
        switch (_state) {
        case CLOSED:
            throw new IllegalStateException("Handle is closed");

        case OPEN:
            fail();
            setState(HandleState.CLOSED);
            break;

        case COMMITTED:
            setState(HandleState.CLOSED);
            break;
        }
        _repository.destroyWhenRemovedAndUnused(_entry);
    }

    /**
     * @return disk file
     * @throws IllegalStateException if EntryIODescriptor is closed.
     */
    @Override
    public synchronized File getFile() throws IllegalStateException
    {
        if (_state == HandleState.CLOSED) {
            throw new IllegalStateException("Handle is closed");
        }

        return _entry.getDataFile();
    }

    /**
     *
     * @return cache entry
     * @throws IllegalStateException
     */
    @Override
    public synchronized CacheEntry getEntry()  throws IllegalStateException
    {
        if (_state == HandleState.CLOSED) {
            throw new IllegalStateException("Handle is closed");
        }

        return new CacheEntryImpl(_entry);
    }
}
