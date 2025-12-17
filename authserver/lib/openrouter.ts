/**
 * OpenRouter API key provisioning.
 *
 * Creates per-user API keys with spending limits.
 * Docs: https://openrouter.ai/docs/api-keys
 */

const OPENROUTER_API_URL = 'https://openrouter.ai/api/v1';

export interface ProvisionedKey {
  key: string;
  name: string;
  limit: number;
  usage: number;
}

/**
 * Create a new API key for a user with a spending limit.
 */
export async function createUserKey(
  userEmail: string,
  limitDollars: number = 1.0
): Promise<string> {
  const provisioningKey = process.env.OPENROUTER_PROVISIONING_KEY;

  if (!provisioningKey) {
    throw new Error('OPENROUTER_PROVISIONING_KEY not configured');
  }

  const response = await fetch(`${OPENROUTER_API_URL}/keys`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${provisioningKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      name: `strawberry-${userEmail}`,
      limit: limitDollars,
    }),
  });

  if (!response.ok) {
    const error = await response.text();
    console.error('OpenRouter key creation failed:', error);
    throw new Error(`Failed to create OpenRouter key: ${response.status}`);
  }

  const data = await response.json();
  return data.key;
}

/**
 * Get info about an existing key (usage, limit, etc.)
 */
export async function getKeyInfo(apiKey: string): Promise<ProvisionedKey | null> {
  const provisioningKey = process.env.OPENROUTER_PROVISIONING_KEY;

  if (!provisioningKey) {
    return null;
  }

  try {
    const response = await fetch(`${OPENROUTER_API_URL}/auth/key`, {
      headers: {
        'Authorization': `Bearer ${apiKey}`,
      },
    });

    if (!response.ok) {
      return null;
    }

    return await response.json();
  } catch {
    return null;
  }
}

/**
 * Revoke a user's API key (for abuse cases).
 */
export async function revokeKey(keyHash: string): Promise<boolean> {
  const provisioningKey = process.env.OPENROUTER_PROVISIONING_KEY;

  if (!provisioningKey) {
    return false;
  }

  try {
    const response = await fetch(`${OPENROUTER_API_URL}/keys/${keyHash}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${provisioningKey}`,
      },
    });

    return response.ok;
  } catch {
    return false;
  }
}
