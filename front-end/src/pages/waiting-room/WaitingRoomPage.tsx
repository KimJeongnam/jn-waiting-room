import {
  Container,
  Loader,
  Paper,
  Progress,
  Stack,
  Text,
  Title,
} from '@mantine/core'

import { formatEstimatedWaitTime } from './formatEstimatedWaitTime'
import './WaitingRoomPage.css'

const WAITING_COUNT = '12,345 명'
const ESTIMATED_WAIT_SECONDS = 3723
const QUEUE_PROGRESS = 65

/** 접속 순서를 기다리는 사용자에게 현재 대기 상태를 안내한다. */
export function WaitingRoomPage() {
  return (
    <main className="queue-page">
      <Container size="xs" w="100%">
        <Paper
          aria-labelledby="queue-title"
          className="queue-card"
          component="section"
          p={{ base: 'xl', sm: 40 }}
          radius="lg"
          shadow="md"
        >
          <Stack align="center" gap="xl">
            <Loader aria-label="대기열 처리 중" size="lg" type="dots" />

            <Title id="queue-title" order={1} ta="center">
              사용자가 많아 접속 대기중입니다.
            </Title>

            <Stack className="queue-stats" gap="xl" role="status">
              <Stack align="center" className="queue-stat" gap={0}>
                <Text c="dimmed" fw={500}>
                  대기 인원
                </Text>
                <Text className="queue-count queue-value" fw={700}>
                  {WAITING_COUNT}
                </Text>
              </Stack>

              <Progress
                aria-label="대기열 진행률"
                className="queue-progress"
                color="blue"
                radius="xl"
                size="md"
                value={QUEUE_PROGRESS}
              />

              <Stack align="center" className="queue-stat" gap={0}>
                <Text c="dimmed" fw={500}>
                  예상 대기 시간
                </Text>
                <Text className="queue-value" fw={700} size="xl">
                  {formatEstimatedWaitTime(ESTIMATED_WAIT_SECONDS)}
                </Text>
              </Stack>
            </Stack>

            <Text c="dimmed" className="queue-warning" size="sm" ta="center">
              ※ 대기중 새로고침 하거나 다시 접속하시면 대기 순서가 초기화
              되므로 유의 하세요.
            </Text>
          </Stack>
        </Paper>
      </Container>
    </main>
  )
}
