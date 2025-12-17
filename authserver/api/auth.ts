import type { VercelRequest, VercelResponse } from '@vercel/node';
import { OAuth2Client } from 'google-auth-library';
import { getUser, saveUser, User } from '../lib/db';
import { createUserKey } from '../lib/openrouter';

const GOOGLE_CLIENT_ID = process.env.GOOGLE_CLIENT_ID;
const CARTESIA_API_KEY = process.env.CARTESIA_API_KEY;
const OPENROUTER_CREDIT_LIMIT = parseFloat(process.env.OPENROUTER_CREDIT_LIMIT || '1.0');

const googleClient = new OAuth2Client(GOOGLE_CLIENT_ID);

interface AuthResponse {
  success: boolean;
  openRouterKey?: string;
  cartesiaKey?: string;
  user?: {
    email: string;
    name: string;
    picture?: string;
  };
  error?: string;
}

export default async function handler(
  req: VercelRequest,
  res: VercelResponse
): Promise<void> {
  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.status(200).end();
    return;
  }

  if (req.method !== 'POST') {
    res.status(405).json({ success: false, error: 'Method not allowed' });
    return;
  }

  try {
    const { idToken } = req.body;

    if (!idToken) {
      res.status(400).json({ success: false, error: 'Missing idToken' });
      return;
    }

    if (!GOOGLE_CLIENT_ID) {
      res.status(500).json({ success: false, error: 'Server misconfigured: missing GOOGLE_CLIENT_ID' });
      return;
    }

    // Verify Google ID token
    const ticket = await googleClient.verifyIdToken({
      idToken,
      audience: GOOGLE_CLIENT_ID,
    });

    const payload = ticket.getPayload();
    if (!payload || !payload.email) {
      res.status(401).json({ success: false, error: 'Invalid token' });
      return;
    }

    const email = payload.email;
    const name = payload.name || email;
    const picture = payload.picture;

    console.log(`Auth request from: ${email}`);

    // Get or create user
    let user = await getUser(email);
    let openRouterKey: string;

    if (user) {
      // Existing user - update last login
      user.lastLoginAt = new Date().toISOString();
      openRouterKey = user.openRouterKey || '';

      // If they don't have a key yet (legacy user), create one
      if (!openRouterKey) {
        console.log(`Creating OpenRouter key for existing user: ${email}`);
        openRouterKey = await createUserKey(email, OPENROUTER_CREDIT_LIMIT);
        user.openRouterKey = openRouterKey;
      }

      await saveUser(user);
    } else {
      // New user - create OpenRouter key and save
      console.log(`New user signup: ${email}`);

      openRouterKey = await createUserKey(email, OPENROUTER_CREDIT_LIMIT);

      user = {
        email,
        name,
        picture,
        openRouterKey,
        createdAt: new Date().toISOString(),
        lastLoginAt: new Date().toISOString(),
        cartesiaCallCount: 0,
      };

      await saveUser(user);
    }

    const response: AuthResponse = {
      success: true,
      openRouterKey,
      cartesiaKey: CARTESIA_API_KEY,
      user: {
        email,
        name,
        picture,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    console.error('Auth error:', error);

    const message = error instanceof Error ? error.message : 'Unknown error';
    res.status(500).json({ success: false, error: message });
  }
}
