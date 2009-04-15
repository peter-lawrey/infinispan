package org.infinispan.container;

import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.InternalEntryFactory;

/**
 * // TODO: Manik: Document this
 *
 * @author Manik Surtani
 * @since 4.0
 */
public class NewLRUContainer extends NewFIFOContainer {

   @Override
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
            updateLinks(le);
         }
      }
      return ice;
   }

   @Override
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
         } else {
            updateLinks(le);
         }
         
      } finally {
         s.unlock();
      }
   }

   protected final void updateLinks(LinkedEntry le) {
      unlink(le);
      linkAtEnd(le);
   }
}