/* RNNoise 0.2's vec_neon.h (and its plain C path) include libopus's os_support.h, which the RNNoise
   release doesn't ship. These are the few things they use from it. */
#ifndef MUNINN_RNNOISE_OS_SUPPORT_H
#define MUNINN_RNNOISE_OS_SUPPORT_H

#include <string.h>
#include <stdlib.h>

#ifndef OPUS_INLINE
#define OPUS_INLINE inline
#endif
#define OPUS_COPY(dst, src, n) (memcpy((dst), (src), (n)*sizeof(*(dst)) + 0*((dst)-(src)) ))
#define OPUS_MOVE(dst, src, n) (memmove((dst), (src), (n)*sizeof(*(dst)) + 0*((dst)-(src)) ))
#define OPUS_CLEAR(dst, n) (memset((dst), 0, (n)*sizeof(*(dst))))

#endif
