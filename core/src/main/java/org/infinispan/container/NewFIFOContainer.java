package org.infinispan.container;

import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.InternalEntryFactory;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

/**
 * // TODO: Manik: Document this
 *
 * @author Manik Surtani
 * @since 4.0
 */
public class NewFIFOContainer implements DataContainer {

   /**
    * The maximum capacity, used if a higher value is implicitly specified by either of the constructors with arguments.
    * MUST be a power of two <= 1<<30 to ensure that entries are indexable using ints.
    */
   static final int MAXIMUM_CAPACITY = 1 << 30;

   // -- these fields are all very similar to JDK's ConcurrentHashMap

   /**
    * Mask value for indexing into segments. The upper bits of a key's hash code are used to choose the segment.
    */
   final int segmentMask;

   /**
    * Shift value for indexing within segments.
    */
   final int segmentShift;

   /**
    * The segments, each of which is a specialized hash table
    */
   final Segment[] segments;

   Set<Object> keySet;

   final LinkedEntry head = new LinkedEntry(), tail = new LinkedEntry();

   public NewFIFOContainer() {
      float loadFactor = 0.75f;
      int initialCapacity = 16;
      int concurrencyLevel = 16;

      // Find power-of-two sizes best matching arguments
      int sshift = 0;
      int ssize = 1;
      while (ssize < concurrencyLevel) {
         ++sshift;
         ssize <<= 1;
      }
      segmentShift = 32 - sshift;
      segmentMask = ssize - 1;
      this.segments = Segment.newArray(ssize);

      if (initialCapacity > MAXIMUM_CAPACITY)
         initialCapacity = MAXIMUM_CAPACITY;
      int c = initialCapacity / ssize;
      if (c * ssize < initialCapacity)
         ++c;
      int cap = 1;
      while (cap < c)
         cap <<= 1;

      for (int i = 0; i < this.segments.length; ++i) this.segments[i] = new Segment(cap, loadFactor);
      initLinks();
   }

   // links and link management

   static final class LinkedEntry {
      volatile InternalCacheEntry e;
      volatile LinkedEntry n, p;

      private static final AtomicReferenceFieldUpdater<LinkedEntry, InternalCacheEntry> E_UPDATER = AtomicReferenceFieldUpdater.newUpdater(LinkedEntry.class, InternalCacheEntry.class, "e");
      private static final AtomicReferenceFieldUpdater<LinkedEntry, LinkedEntry> N_UPDATER = AtomicReferenceFieldUpdater.newUpdater(LinkedEntry.class, LinkedEntry.class, "n");
      private static final AtomicReferenceFieldUpdater<LinkedEntry, LinkedEntry> P_UPDATER = AtomicReferenceFieldUpdater.newUpdater(LinkedEntry.class, LinkedEntry.class, "p");

      final boolean casValue(InternalCacheEntry expected, InternalCacheEntry newValue) {
         return E_UPDATER.compareAndSet(this, expected, newValue);
      }

      final boolean casNext(LinkedEntry expected, LinkedEntry newValue) {
         return N_UPDATER.compareAndSet(this, expected, newValue);
      }

      final boolean casPrev(LinkedEntry expected, LinkedEntry newValue) {
         return P_UPDATER.compareAndSet(this, expected, newValue);
      }

      final void mark() {
         e = null;
      }

      final boolean isMarked() {
         return e == null; // an impossible value unless deleted
      }
   }

   /**
    * Initializes links to an empty container
    */
   protected final void initLinks() {
      head.n = tail;
      head.p = tail;
      tail.n = head;
      tail.p = head;
   }

   protected final void unlink(LinkedEntry le) {
      le.p.casNext(le, le.n);
      le.n.casPrev(le, le.p);
   }

   protected final void linkAtEnd(LinkedEntry le) {
      le.n = tail;
      do {
         le.p = tail.p;
      } while (!le.p.casNext(tail, le));
      tail.p = le;
   }

   /**
    * Similar to ConcurrentHashMap's hash() function: applies a supplemental hash function to a given hashCode, which
    * defends against poor quality hash functions.  This is critical because ConcurrentHashMap uses power-of-two length
    * hash tables, that otherwise encounter collisions for hashCodes that do not differ in lower or upper bits.
    */
   final int hash(int h) {
      // Spread bits to regularize both segment and index locations,
      // using variant of single-word Wang/Jenkins hash.
      h += (h << 15) ^ 0xffffcd7d;
      h ^= (h >>> 10);
      h += (h << 3);
      h ^= (h >>> 6);
      h += (h << 2) + (h << 14);
      return h ^ (h >>> 16);
   }

   /**
    * Returns the segment that should be used for key with given hash
    *
    * @param hash the hash code for the key
    * @return the segment
    */
   final Segment segmentFor(int hash) {
      return segments[(hash >>> segmentShift) & segmentMask];
   }

   /**
    * ConcurrentHashMap list entry. Note that this is never exported out as a user-visible Map.Entry.
    * <p/>
    * Because the value field is volatile, not final, it is legal wrt the Java Memory Model for an unsynchronized reader
    * to see null instead of initial value when read via a data race.  Although a reordering leading to this is not
    * likely to ever actually occur, the Segment.readValueUnderLock method is used as a backup in case a null
    * (pre-initialized) value is ever seen in an unsynchronized access method.
    */
   static final class HashEntry {
      final Object key;
      final int hash;
      volatile LinkedEntry value;
      final HashEntry next;

      HashEntry(Object key, int hash, HashEntry next, LinkedEntry value) {
         this.key = key;
         this.hash = hash;
         this.next = next;
         this.value = value;
      }
   }

   /**
    * Very similar to a Segment in a ConcurrentHashMap
    */
   static final class Segment extends ReentrantLock {
      /**
       * The number of elements in this segment's region.
       */
      transient volatile int count;

      /**
       * The table is rehashed when its size exceeds this threshold. (The value of this field is always
       * <tt>(int)(capacity * loadFactor)</tt>.)
       */
      transient int threshold;

      /**
       * The per-segment table.
       */
      transient volatile HashEntry[] table;

      /**
       * The load factor for the hash table.  Even though this value is same for all segments, it is replicated to avoid
       * needing links to outer object.
       *
       * @serial
       */
      final float loadFactor;

      Segment(int initialCapacity, float lf) {
         loadFactor = lf;
         setTable(new HashEntry[initialCapacity]);
      }

      static final Segment[] newArray(int i) {
         return new Segment[i];
      }

      /**
       * Sets table to new HashEntry array. Call only while holding lock or in constructor.
       */
      final void setTable(HashEntry[] newTable) {
         threshold = (int) (newTable.length * loadFactor);
         table = newTable;
      }

      /**
       * Returns properly casted first entry of bin for given hash.
       */
      final HashEntry getFirst(int hash) {
         HashEntry[] tab = table;
         return tab[hash & (tab.length - 1)];
      }

      /**
       * Reads value field of an entry under lock. Called if value field ever appears to be null. This is possible only
       * if a compiler happens to reorder a HashEntry initialization with its table assignment, which is legal under
       * memory model but is not known to ever occur.
       */
      final LinkedEntry readValueUnderLock(HashEntry e) {
         lock();
         try {
            return e.value;
         } finally {
            unlock();
         }
      }

      /* Specialized implementations of map methods */

      final LinkedEntry get(Object key, int hash) {
         if (count != 0) { // read-volatile
            HashEntry e = getFirst(hash);
            while (e != null) {
               if (e.hash == hash && key.equals(e.key)) {
                  LinkedEntry v = e.value;
                  if (v != null)
                     return v;
                  return readValueUnderLock(e); // recheck
               }
               e = e.next;
            }
         }
         return null;
      }

      /**
       * This put is lockless.  Make sure you call segment.lock() first.
       */
      final LinkedEntry locklessPut(Object key, int hash, LinkedEntry value) {
         int c = count;
         if (c++ > threshold) // ensure capacity
            rehash();
         HashEntry[] tab = table;
         int index = hash & (tab.length - 1);
         HashEntry first = tab[index];
         HashEntry e = first;
         while (e != null && (e.hash != hash || !key.equals(e.key)))
            e = e.next;

         LinkedEntry oldValue;
         if (e != null) {
            oldValue = e.value;
            e.value = value;
         } else {
            oldValue = null;
            tab[index] = new HashEntry(key, hash, first, value);
            count = c; // write-volatile
         }
         return oldValue;
      }

      final void rehash() {
         HashEntry[] oldTable = table;
         int oldCapacity = oldTable.length;
         if (oldCapacity >= MAXIMUM_CAPACITY)
            return;

         /*
         * Reclassify nodes in each list to new Map.  Because we are
         * using power-of-two expansion, the elements from each bin
         * must either stay at same index, or move with a power of two
         * offset. We eliminate unnecessary node creation by catching
         * cases where old nodes can be reused because their next
         * fields won't change. Statistically, at the default
         * threshold, only about one-sixth of them need cloning when
         * a table doubles. The nodes they replace will be garbage
         * collectable as soon as they are no longer referenced by any
         * reader thread that may be in the midst of traversing table
         * right now.
         */

         HashEntry[] newTable = new HashEntry[oldCapacity << 1];
         threshold = (int) (newTable.length * loadFactor);
         int sizeMask = newTable.length - 1;
         for (int i = 0; i < oldCapacity; i++) {
            // We need to guarantee that any existing reads of old Map can
            //  proceed. So we cannot yet null out each bin.
            HashEntry e = oldTable[i];

            if (e != null) {
               HashEntry next = e.next;
               int idx = e.hash & sizeMask;

               //  Single node on list
               if (next == null)
                  newTable[idx] = e;

               else {
                  // Reuse trailing consecutive sequence at same slot
                  HashEntry lastRun = e;
                  int lastIdx = idx;
                  for (HashEntry last = next;
                       last != null;
                       last = last.next) {
                     int k = last.hash & sizeMask;
                     if (k != lastIdx) {
                        lastIdx = k;
                        lastRun = last;
                     }
                  }
                  newTable[lastIdx] = lastRun;

                  // Clone all remaining nodes
                  for (HashEntry p = e; p != lastRun; p = p.next) {
                     int k = p.hash & sizeMask;
                     HashEntry n = newTable[k];
                     newTable[k] = new HashEntry(p.key, p.hash, n, p.value);
                  }
               }
            }
         }
         table = newTable;
      }

      /**
       * This is a lockless remove.  Make sure you acquire locks using segment.lock() first.
       */
      final LinkedEntry locklessRemove(Object key, int hash) {
         int c = count - 1;
         HashEntry[] tab = table;
         int index = hash & (tab.length - 1);
         HashEntry first = tab[index];
         HashEntry e = first;
         while (e != null && (e.hash != hash || !key.equals(e.key)))
            e = e.next;

         LinkedEntry oldValue = null;
         if (e != null) {
            oldValue = e.value;
            // All entries following removed node can stay
            // in list, but all preceding ones need to be
            // cloned.
            HashEntry newFirst = e.next;
            for (HashEntry p = first; p != e; p = p.next)
               newFirst = new HashEntry(p.key, p.hash,
                                        newFirst, p.value);
            tab[index] = newFirst;
            count = c; // write-volatile

         }
         return oldValue;
      }

      /**
       * This is a lockless clear.  Ensure you acquire locks on the segment first using segment.lock().
       */
      final void locklessClear() {
         if (count != 0) {
            HashEntry[] tab = table;
            for (int i = 0; i < tab.length; i++)
               tab[i] = null;
            count = 0; // write-volatile
         }
      }
   }


   protected final class KeySet extends AbstractSet<Object> {

      public Iterator<Object> iterator() {
         return new KeyIterator();
      }

      public int size() {
         return NewFIFOContainer.this.size();
      }
   }

   protected abstract class LinkedIterator {
      LinkedEntry current = head;

      public boolean hasNext() {
         current = current.n;
         while (current.isMarked()) {
            if (current == tail || current == head) return false;
            current = current.n;
         }
         return true;
      }

      public void remove() {
         throw new UnsupportedOperationException();
      }
   }

   protected final class ValueIterator extends LinkedIterator implements Iterator<InternalCacheEntry> {
      public InternalCacheEntry next() {
         while (current.isMarked()) {
            LinkedEntry n = current.n;
            unlink(current);
            current = n;
            if (n == head || n == tail) throw new IndexOutOfBoundsException("Reached head or tail pointer!");
         }
         
         return current.e;
      }
   }

   protected final class KeyIterator extends LinkedIterator implements Iterator<Object> {
      public Object next() {
         while (current.isMarked()) {
            LinkedEntry n = current.n;
            unlink(current);
            current = n;
            if (n == head || n == tail) throw new IndexOutOfBoundsException("Reached head or tail pointer!");
         }

         return current.e.getKey();
      }
   }


   // ----------- PUBLIC API ---------------

   public InternalCacheEntry get(Object k) {
      int h = hash(k.hashCode());
      Segment s = segmentFor(h);
      LinkedEntry le = s.get(k, h);
      InternalCacheEntry ice = null;
      if (le != null) {
         ice = le.e;
         if (le.isMarked()) unlink(le);
      }
      if (ice != null) {
         if (ice.isExpired()) {
            remove(k);
            ice = null;
         } else {
            ice.touch();
         }
      }
      return ice;
   }

   public void put(Object k, Object v, long lifespan, long maxIdle) {
      // do a normal put first.
      int h = hash(k.hashCode());
      Segment s = segmentFor(h);
      s.lock();
      LinkedEntry le;
      boolean newEntry = false;
      try {
         le = s.get(k, h);
         InternalCacheEntry ice = le == null ? null : le.e;
         if (ice == null) {
            newEntry = true;
            ice = InternalEntryFactory.create(k, v, lifespan, maxIdle);
            // only update linking if this is a new entry
            le = new LinkedEntry();
         } else {
            ice.setValue(v);
            ice = ice.setLifespan(lifespan).setMaxIdle(maxIdle);
         }

         // need to do this anyway since the ICE impl may have changed
         le.e = ice;
         s.locklessPut(k, h, le);

         if (newEntry) {
            linkAtEnd(le);
         }
      } finally {
         s.unlock();
      }
   }

   public boolean containsKey(Object k) {
      int h = hash(k.hashCode());
      Segment s = segmentFor(h);
      LinkedEntry le = s.get(k, h);
      InternalCacheEntry ice = null;
      if (le != null) {
         ice = le.e;
         if (le.isMarked()) unlink(le);
      }
      if (ice != null) {
         if (ice.isExpired()) {
            remove(k);
            ice = null;
         }
      }

      return ice != null;
   }

   public InternalCacheEntry remove(Object k) {
      int h = hash(k.hashCode());
      Segment s = segmentFor(h);
      s.lock();
      InternalCacheEntry ice = null;
      LinkedEntry le;
      try {
         le = s.locklessRemove(k, h);
         if (le != null) {
            ice = le.e;
            le.mark();
            unlink(le);
         }
      } finally {
         s.unlock();
      }

      if (ice == null || ice.isExpired())
         return null;
      else
         return ice;
   }

   public int size() {
      // approximate sizing is good enough
      int sz = 0;
      final Segment[] segs = segments;
      for (Segment s : segs) sz += s.count;
      return sz;
   }

   public void clear() {
      // This is expensive...
      // lock all segments
      for (Segment s : segments) s.lock();
      try {
         for (Segment s : segments) s.locklessClear();
         initLinks();
      } finally {
         for (Segment s : segments) s.unlock();
      }
   }

   public Set<Object> keySet() {
      if (keySet == null) keySet = new KeySet();
      return keySet;
   }

   public void purgeExpired() {
      for (InternalCacheEntry ice : this) {
         if (ice.isExpired()) remove(ice.getKey());
      }
   }

   public Iterator<InternalCacheEntry> iterator() {
      return new ValueIterator();
   }
}
