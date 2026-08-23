/** Mood face glyphs — parity with Android MoodFaces.kt. */
export const MOOD_FACES = ['😞', '🙁', '😐', '🙂', '🌟'] as const

export const MOOD_FACE_DESCRIPTIONS = [
  'Very low mood',
  'Low mood',
  'Neutral mood',
  'Good mood',
  'Great mood',
] as const

export function moodFace(mood: number | null | undefined): string | null {
  if (mood == null || mood < 1 || mood > 5) return null
  return MOOD_FACES[mood - 1] ?? null
}
