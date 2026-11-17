// The front desk's owner card, read from the petclinic API (docs/openapi.yaml).

export interface OwnerCard {
  id: number;
  firstName: string;
  lastName: string;
  telephone: string;
  pets: number;
}

const BASE: string = process.env.PETCLINIC_URL ?? 'http://localhost:8080';

const api = {
  async get<T>(path: string): Promise<T> {
    const res = await fetch(BASE + path);
    if (!res.ok) throw new Error(`${res.status} ${path}`);
    return (await res.json()) as T;
  },
};

/** "George Franklin, tel. 6085551023 (1 pet)" */
export async function cardLine(ownerId: number): Promise<string> {
  const card = await api.get<OwnerCard>(`/api/owners/${ownerId}/card`);
  const pets = card.pets === 1 ? '1 pet' : `${card.pets} pets`;
  return `${card.firstName} ${card.lastName}, tel. ${card.telephone} (${pets})`;
}
