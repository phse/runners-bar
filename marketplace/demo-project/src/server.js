import { createServer } from 'node:http'

const port = process.env.PORT ?? 3000

createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' })
  res.end('Hello from demo-shop\n')
}).listen(port, () => {
  console.log(`Server running on http://localhost:${port}`)
})
