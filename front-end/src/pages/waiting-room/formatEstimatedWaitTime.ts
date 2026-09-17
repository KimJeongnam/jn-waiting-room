/** 남은 대기 시간을 표시할 때 필요하지 않은 상위 시간 단위는 생략한다. */
export function formatEstimatedWaitTime(totalSeconds: number) {
  const seconds = totalSeconds % 60
  const formattedSeconds = String(seconds).padStart(2, '0')

  if (totalSeconds < 60) {
    return `${formattedSeconds}초`
  }

  const totalMinutes = Math.floor(totalSeconds / 60)
  const minutes = totalMinutes % 60
  const formattedMinutes = String(minutes).padStart(2, '0')

  if (totalSeconds < 3600) {
    return `${formattedMinutes}분 ${formattedSeconds}초`
  }

  const hours = Math.floor(totalMinutes / 60)
  const formattedHours = String(hours).padStart(2, '0')

  return `${formattedHours}시간 ${formattedMinutes}분 ${formattedSeconds}초`
}
