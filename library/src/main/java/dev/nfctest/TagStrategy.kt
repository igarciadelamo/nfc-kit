package dev.nfctest

/**
 * Strategy used by [TagFactory] to build a fake [android.nfc.Tag].
 *
 * - [PARCEL]: hand-written Parcel decoded with the public `Tag.CREATOR`.
 * - [REFLECTION]: hidden `Tag.createMockTag` static factory.
 * - [AUTO]: try [PARCEL], self-check, then fall back to [REFLECTION].
 */
enum class TagStrategy { PARCEL, REFLECTION, AUTO }
