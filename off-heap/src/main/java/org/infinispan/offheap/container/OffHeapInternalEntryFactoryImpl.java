package org.infinispan.offheap.container;

import org.infinispan.container.InternalEntryFactory;
import org.infinispan.container.entries.*;
import org.infinispan.container.entries.metadata.MetadataImmortalCacheEntry;
import org.infinispan.container.entries.metadata.MetadataMortalCacheEntry;
import org.infinispan.container.entries.metadata.MetadataTransientCacheEntry;
import org.infinispan.container.entries.metadata.MetadataTransientMortalCacheEntry;
import org.infinispan.container.versioning.EntryVersion;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.metadata.EmbeddedMetadata;
import org.infinispan.metadata.Metadata;
import org.infinispan.offheap.container.entries.*;
import org.infinispan.offheap.container.entries.metadata.*;
import org.infinispan.util.TimeService;

/**
 * An implementation that generates non-versioned entries
 *
 * @author Manik Surtani
 * @since 5.1
 */
public class OffHeapInternalEntryFactoryImpl implements InternalEntryFactory {

   private TimeService timeService;

   @Inject
   public void injectTimeService(TimeService timeService) {
      this.timeService = timeService;
   }

   @Override
   public InternalCacheEntry create(Object key, Object value, Metadata metadata) {
      long lifespan = metadata != null ? metadata.lifespan() : -1;
      long maxIdle = metadata != null ? metadata.maxIdle() : -1;
      if (!isStoreMetadata(metadata)) {
         if (lifespan < 0 && maxIdle < 0) return new OffHeapImmortalCacheEntry(key, value);
         if (lifespan > -1 && maxIdle < 0) return new OffHeapMortalCacheEntry(key, value, lifespan, timeService.wallClockTime());
         if (lifespan < 0 && maxIdle > -1) return new OffHeapTransientCacheEntry(key, value, maxIdle, timeService.wallClockTime());
         return new OffHeapTransientMortalCacheEntry(key, value, maxIdle, lifespan, timeService.wallClockTime());
      } else {
         if (lifespan < 0 && maxIdle < 0) return new OffHeapMetadataImmortalCacheEntry(key, value, metadata);
         if (lifespan > -1 && maxIdle < 0) return new OffHeapMetadataMortalCacheEntry(key, value, metadata, timeService.wallClockTime());
         if (lifespan < 0 && maxIdle > -1) return new OffHeapMetadataTransientCacheEntry(key, value, metadata, timeService.wallClockTime());
         return new OffHeapMetadataTransientMortalCacheEntry(key, value, metadata, timeService.wallClockTime());
      }
   }

   @Override
   public InternalCacheEntry create(CacheEntry cacheEntry) {
      return create(
                cacheEntry.getKey(),
                cacheEntry.getValue(),
                cacheEntry.getMetadata(),
                cacheEntry.getLifespan(),
                cacheEntry.getMaxIdle()
      );
   }

   @Override
   public InternalCacheEntry create(Object key, Object value, InternalCacheEntry cacheEntry) {
      return create(key, value, cacheEntry.getMetadata(), cacheEntry.getCreated(),
            cacheEntry.getLifespan(), cacheEntry.getLastUsed(), cacheEntry.getMaxIdle());
   }




    @Override
   public InternalCacheEntry create(Object key, Object value, EntryVersion version, long created, long lifespan, long lastUsed, long maxIdle) {
      if (version == null) {
         if (lifespan < 0 && maxIdle < 0) return new ImmortalCacheEntry(key, value);
         if (lifespan > -1 && maxIdle < 0) return new MortalCacheEntry(key, value, lifespan, created);
         if (lifespan < 0 && maxIdle > -1) return new TransientCacheEntry(key, value, maxIdle, lastUsed);
         return new TransientMortalCacheEntry(key, value, maxIdle, lifespan, lastUsed, created);
      } else {
         // If no metadata passed, assumed embedded metadata
         Metadata metadata = new EmbeddedMetadata.Builder()
                                        .lifespan(lifespan)
                                        .maxIdle(maxIdle)
                                        .version(version)
                                        .build();

         if (lifespan < 0 && maxIdle < 0) return new MetadataImmortalCacheEntry(key, value, metadata);
         if (lifespan > -1 && maxIdle < 0) return new MetadataMortalCacheEntry(key, value, metadata, created);
         if (lifespan < 0 && maxIdle > -1) return new MetadataTransientCacheEntry(key, value, metadata, lastUsed);
         return new MetadataTransientMortalCacheEntry(key, value, metadata, lastUsed, created);
      }
   }


    @Override
   public InternalCacheEntry create(Object key, Object value, Metadata metadata, long created, long lifespan, long lastUsed, long maxIdle) {
      if (!isStoreMetadata(metadata)) {
         if (lifespan < 0 && maxIdle < 0) return new ImmortalCacheEntry(key, value);
         if (lifespan > -1 && maxIdle < 0) return new MortalCacheEntry(key, value, lifespan, created);
         if (lifespan < 0 && maxIdle > -1) return new TransientCacheEntry(key, value, maxIdle, lastUsed);
         return new TransientMortalCacheEntry(key, value, maxIdle, lifespan, lastUsed, created);
      } else {
         // Metadata to store, take lifespan and maxIdle settings from it
         long metaLifespan = metadata.lifespan();
         long metaMaxIdle = metadata.maxIdle();
         if (metaLifespan < 0 && metaMaxIdle < 0) return new MetadataImmortalCacheEntry(key, value, metadata);
         if (metaLifespan > -1 && metaMaxIdle < 0) return new MetadataMortalCacheEntry(key, value, metadata, created);
         if (metaLifespan < 0 && metaMaxIdle > -1) return new MetadataTransientCacheEntry(key, value, metadata, lastUsed);
         return new OffHeapMetadataTransientMortalCacheEntry(key, value, metadata, lastUsed, created);
      }
   }

   @Override
   public InternalCacheValue createValue(CacheEntry cacheEntry) {
      Metadata metadata = cacheEntry.getMetadata();
      long lifespan = cacheEntry.getLifespan();
      long maxIdle = cacheEntry.getMaxIdle();
      if (!isStoreMetadata(metadata)) {
         if (lifespan < 0 && maxIdle < 0) return new OffHeapImmortalCacheValue(cacheEntry.getValue());
         if (lifespan > -1 && maxIdle < 0) return new OffHeapMortalCacheValue(cacheEntry.getValue(), -1, lifespan);
         if (lifespan < 0 && maxIdle > -1) return new OffHeapTransientCacheValue(cacheEntry.getValue(), maxIdle, -1);
         return new OffHeapTransientMortalCacheValue(cacheEntry.getValue(), -1, lifespan, maxIdle, -1);
      } else {
         if (lifespan < 0 && maxIdle < 0) return new OffHeapMetadataImmortalCacheValue(cacheEntry.getValue(), cacheEntry.getMetadata());
         if (lifespan > -1 && maxIdle < 0) return new OffHeapMetadataMortalCacheValue(cacheEntry.getValue(), cacheEntry.getMetadata(), -1);
         if (lifespan < 0 && maxIdle > -1) return new OffHeapMetadataTransientCacheValue(cacheEntry.getValue(), cacheEntry.getMetadata(), -1);
         return new OffHeapMetadataTransientMortalCacheValue(cacheEntry.getValue(), cacheEntry.getMetadata(), -1, -1);
      }
   }

   @Override
   // TODO: Do we need this???
   public InternalCacheEntry create(Object key, Object value, Metadata metadata, long lifespan, long maxIdle) {
      if (!isStoreMetadata(metadata)) {
         if (lifespan < 0 && maxIdle < 0) return new OffHeapImmortalCacheEntry(key, value);
         if (lifespan > -1 && maxIdle < 0) return new OffHeapMortalCacheEntry(key, value, lifespan, timeService.wallClockTime());
         if (lifespan < 0 && maxIdle > -1) return new OffHeapTransientCacheEntry(key, value, maxIdle, timeService.wallClockTime());
         return new OffHeapTransientMortalCacheEntry(key, value, maxIdle, lifespan, timeService.wallClockTime());
      } else {
         // Metadata to store, take lifespan and maxIdle settings from it
         long metaLifespan = metadata.lifespan();
         long metaMaxIdle = metadata.maxIdle();
         if (metaLifespan < 0 && metaMaxIdle < 0) return new OffHeapMetadataImmortalCacheEntry(key, value, metadata);
         if (metaLifespan > -1 && metaMaxIdle < 0) return new OffHeapMetadataMortalCacheEntry(key, value, metadata, timeService.wallClockTime());
         if (metaLifespan < 0 && metaMaxIdle > -1) return new OffHeapMetadataTransientCacheEntry(key, value, metadata, timeService.wallClockTime());
         return new OffHeapMetadataTransientMortalCacheEntry(key, value, metadata, timeService.wallClockTime());
      }
   }


   @Override
   public InternalCacheEntry update(InternalCacheEntry ice, Metadata metadata) {
      if (!isStoreMetadata(metadata))
         return updateMetadataUnawareEntry(ice, metadata.lifespan(), metadata.maxIdle());
      else
         return updateMetadataAwareEntry(ice, metadata);
   }

   private InternalCacheEntry updateMetadataUnawareEntry(InternalCacheEntry ice, long lifespan, long maxIdle) {
      if (ice instanceof OffHeapImmortalCacheEntry) {
         if (lifespan < 0) {
            if (maxIdle < 0) {
               return ice;
            } else {
               return new OffHeapTransientCacheEntry(ice.getKey(), ice.getValue(), maxIdle, timeService.wallClockTime());
            }
         } else {
            if (maxIdle < 0) {
               return new OffHeapMortalCacheEntry(ice.getKey(), ice.getValue(), lifespan, timeService.wallClockTime());
            } else {
               long ctm = timeService.wallClockTime();
               return new OffHeapTransientMortalCacheEntry(ice.getKey(), ice.getValue(), maxIdle, lifespan, ctm, ctm);
            }
         }
      } else if (ice instanceof OffHeapMortalCacheEntry) {
         if (lifespan < 0) {
            if (maxIdle < 0) {
               return new OffHeapImmortalCacheEntry(ice.getKey(), ice.getValue());
            } else {
               return new OffHeapTransientCacheEntry(ice.getKey(), ice.getValue(), maxIdle, timeService.wallClockTime());
            }
         } else {
            if (maxIdle < 0) {
               ((OffHeapMortalCacheEntry) ice).setLifespan(lifespan);
               return ice;
            } else {
               long ctm = timeService.wallClockTime();
               return new OffHeapTransientMortalCacheEntry(ice.getKey(), ice.getValue(), maxIdle, lifespan, ctm, ctm);
            }
         }
      } else if (ice instanceof OffHeapTransientCacheEntry) {
         if (lifespan < 0) {
            if (maxIdle < 0) {
               return new OffHeapImmortalCacheEntry(ice.getKey(), ice.getValue());
            } else {
               ((OffHeapTransientCacheEntry) ice).setMaxIdle(maxIdle);
               return ice;
            }
         } else {
            if (maxIdle < 0) {
               return new OffHeapMortalCacheEntry(ice.getKey(), ice.getValue(), lifespan, timeService.wallClockTime());
            } else {
               long ctm = timeService.wallClockTime();
               return new OffHeapTransientMortalCacheEntry(ice.getKey(), ice.getValue(), maxIdle, lifespan, ctm, ctm);
            }
         }
      } else if (ice instanceof OffHeapTransientMortalCacheEntry) {
         if (lifespan < 0) {
            if (maxIdle < 0) {
               return new OffHeapImmortalCacheEntry(ice.getKey(), ice.getValue());
            } else {
               return new OffHeapTransientCacheEntry(ice.getKey(), ice.getValue(), maxIdle, timeService.wallClockTime());
            }
         } else {
            if (maxIdle < 0) {
               return new OffHeapMortalCacheEntry(ice.getKey(), ice.getValue(), lifespan, timeService.wallClockTime());
            } else {
               OffHeapTransientMortalCacheEntry transientMortalEntry = (OffHeapTransientMortalCacheEntry) ice;
               transientMortalEntry.setLifespan(lifespan);
               transientMortalEntry.setMaxIdle(maxIdle);
               return ice;
            }
         }
      }
      return ice;
   }

   private InternalCacheEntry updateMetadataAwareEntry(InternalCacheEntry ice, Metadata metadata) {
      long lifespan = metadata.lifespan();
      long maxIdle = metadata.maxIdle();
      if (ice instanceof MetadataImmortalCacheEntry) {
         if (lifespan < 0) {
            if (maxIdle < 0) {
               ice.setMetadata(metadata);
               return ice;
            } else {
               return new OffHeapMetadataTransientCacheEntry(ice.getKey(), ice.getValue(), metadata, timeService.wallClockTime());
            }
         } else {
            if (maxIdle < 0) {
               return new OffHeapMetadataMortalCacheEntry(ice.getKey(), ice.getValue(), metadata, timeService.wallClockTime());
            } else {
               long ctm = timeService.wallClockTime();
               return new OffHeapMetadataTransientMortalCacheEntry(ice.getKey(), ice.getValue(), metadata, ctm, ctm);
            }
         }
      } else if (ice instanceof MetadataMortalCacheEntry) {
         if (lifespan < 0) {
            if (maxIdle < 0) {
               return new OffHeapMetadataImmortalCacheEntry(ice.getKey(), ice.getValue(), metadata);
            } else {
               return new OffHeapMetadataTransientCacheEntry(ice.getKey(), ice.getValue(), metadata, timeService.wallClockTime());
            }
         } else {
            if (maxIdle < 0) {
               ice.setMetadata(metadata);
               return ice;
            } else {
               long ctm = timeService.wallClockTime();
               return new OffHeapMetadataTransientMortalCacheEntry(ice.getKey(), ice.getValue(), metadata, ctm, ctm);
            }
         }
      } else if (ice instanceof MetadataTransientCacheEntry) {
         if (lifespan < 0) {
            if (maxIdle < 0) {
               return new OffHeapMetadataImmortalCacheEntry(ice.getKey(), ice.getValue(), metadata);
            } else {
               ice.setMetadata(metadata);
               return ice;
            }
         } else {
            if (maxIdle < 0) {
               return new OffHeapMetadataMortalCacheEntry(ice.getKey(), ice.getValue(), metadata, timeService.wallClockTime());
            } else {
               long ctm = timeService.wallClockTime();
               return new OffHeapMetadataTransientMortalCacheEntry(ice.getKey(), ice.getValue(), metadata, ctm, ctm);
            }
         }
      } else if (ice instanceof MetadataTransientMortalCacheEntry) {
         if (lifespan < 0) {
            if (maxIdle < 0) {
               return new OffHeapMetadataImmortalCacheEntry(ice.getKey(), ice.getValue(), metadata);
            } else {
               return new OffHeapMetadataTransientCacheEntry(ice.getKey(), ice.getValue(), metadata, maxIdle);
            }
         } else {
            if (maxIdle < 0) {
               return new OffHeapMetadataMortalCacheEntry(ice.getKey(), ice.getValue(), metadata, lifespan);
            } else {
               ice.setMetadata(metadata);
               return ice;
            }
         }
      }
      return ice;
   }

   /**
    * Indicates whether the entire metadata object needs to be stored or not.
    *
    * This check is done to avoid keeping the entire metadata object around
    * when only lifespan or maxIdle time is stored. If more information
    * needs to be stored (i.e. version), or the metadata object is not the
    * embedded one, keep the entire metadata object around.
    *
    * @return true if the entire metadata object needs to be stored, otherwise
    * simply store lifespan and/or maxIdle in existing cache entries
    */
   private boolean isStoreMetadata(Metadata metadata) {
      return metadata != null
            && (metadata.version() != null
                      || !(metadata instanceof EmbeddedMetadata));
   }

}
