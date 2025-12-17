/**
 * Simple user database using Vercel KV (Redis).
 *
 * To use Vercel KV:
 * 1. Go to your Vercel project dashboard
 * 2. Storage → Create → KV
 * 3. Connect to your project
 * 4. Environment variables are auto-added
 *
 * For local dev without KV, falls back to in-memory storage.
 */

export interface User {
  email: string;
  name: string;
  picture?: string;
  openRouterKey?: string;
  createdAt: string;
  lastLoginAt: string;
  cartesiaCallCount: number;
}

// In-memory fallback for local development
const memoryStore: Map<string, User> = new Map();

// Check if Vercel KV is available
const hasKV = !!process.env.KV_REST_API_URL;

async function kvFetch(method: string, args: any[] = []) {
  const response = await fetch(`${process.env.KV_REST_API_URL}`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${process.env.KV_REST_API_TOKEN}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify([method, ...args]),
  });
  const data = await response.json();
  return data.result;
}

export async function getUser(email: string): Promise<User | null> {
  if (!hasKV) {
    return memoryStore.get(email) || null;
  }

  const data = await kvFetch('GET', [`user:${email}`]);
  return data ? JSON.parse(data) : null;
}

export async function saveUser(user: User): Promise<void> {
  if (!hasKV) {
    memoryStore.set(user.email, user);
    return;
  }

  await kvFetch('SET', [`user:${user.email}`, JSON.stringify(user)]);
}

export async function incrementCartesiaCalls(email: string): Promise<number> {
  const user = await getUser(email);
  if (!user) return 0;

  user.cartesiaCallCount += 1;
  user.lastLoginAt = new Date().toISOString();
  await saveUser(user);

  return user.cartesiaCallCount;
}

export async function getAllUsers(): Promise<User[]> {
  if (!hasKV) {
    return Array.from(memoryStore.values());
  }

  // For KV, you'd need to use SCAN - simplified here
  // In production, consider a proper database for listing users
  console.warn('getAllUsers not fully implemented for KV - use dashboard');
  return [];
}
