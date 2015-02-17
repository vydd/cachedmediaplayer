# cachedmediaplayer
Android MediaPlayer wrapper that supports caching via internal proxy.

## Instructions

Add CachedMediaPlayer.java to your Android project and import where needed. 

After initializing a new CachedMediaPlayer object, set your cache directory:

```java
...
CachedMediaPlayer cmp = new CachedMediaPlayer();
cmp.setCacheDir(CACHE_VIDEOS_LOCATION);
...
```

Other methods are extended from MediaPlayer, so you don't need to change anything else.

## Notes

It hasn't been battle tested yet.

Proxy needs some improvements, specifically regarding its cleanup after streaming is completed.
