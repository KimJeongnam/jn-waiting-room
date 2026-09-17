import { Container, Paper, Stack, Text, Title } from '@mantine/core'

import './HomePage.css'

/** 대기열을 통과한 사용자에게 예매 페이지 입장을 안내한다. */
export function HomePage() {
  return (
    <main className="home-page">
      <Container size="xs" w="100%">
        <Paper
          aria-labelledby="home-title"
          className="home-card"
          component="section"
          p={{ base: 'xl', sm: 40 }}
          radius="lg"
          shadow="md"
        >
          <Stack align="center" gap="md">
            <Text c="blue" fw={700}>
              입장 완료
            </Text>

            <Title id="home-title" order={1} ta="center">
              예매 페이지에 입장했습니다.
            </Title>

            <Text c="dimmed" ta="center">
              대기열을 통과했습니다.
            </Text>
          </Stack>
        </Paper>
      </Container>
    </main>
  )
}
