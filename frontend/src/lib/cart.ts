import type { Product } from '../api/types';

/** Largest quantity one line may carry: the backend's per-product limit. */
export const MAX_LINE_QUANTITY = 100;

/** Largest number of distinct lines in one order: the backend's limit. */
export const MAX_CART_LINES = 20;

export interface CartLine {
  productId: string;
  name: string;
  unitPrice: number;
  quantity: number;
}

export type Cart = CartLine[];

/** Clamps a requested quantity into the allowed range; 0 removes the line. */
export function clampQuantity(value: number): number {
  if (!Number.isFinite(value)) {
    return 1;
  }
  return Math.min(MAX_LINE_QUANTITY, Math.max(0, Math.trunc(value)));
}

/** Adds one unit of the product, or sets the quantity of an existing line. */
export function addToCart(cart: Cart, product: Product, quantity = 1): Cart {
  const existing = cart.find((line) => line.productId === product.id);
  if (existing !== undefined) {
    return setQuantity(cart, product.id, existing.quantity + quantity, product);
  }
  if (cart.length >= MAX_CART_LINES || clampQuantity(quantity) === 0) {
    return cart;
  }
  return [
    ...cart,
    {
      productId: product.id,
      name: product.name,
      unitPrice: product.price,
      quantity: clampQuantity(quantity),
    },
  ];
}

/** Sets a line's quantity; 0 removes it. Needs the product only when the line is new. */
export function setQuantity(
  cart: Cart,
  productId: string,
  quantity: number,
  product?: Product,
): Cart {
  const next = clampQuantity(quantity);
  if (next === 0) {
    return cart.filter((line) => line.productId !== productId);
  }
  if (!cart.some((line) => line.productId === productId)) {
    if (product === undefined || cart.length >= MAX_CART_LINES) {
      return cart;
    }
    return [
      ...cart,
      { productId, name: product.name, unitPrice: product.price, quantity: next },
    ];
  }
  return cart.map((line) => (line.productId === productId ? { ...line, quantity: next } : line));
}

/** Sum of unit price × quantity, rounded to cents. The server recomputes the real total. */
export function cartTotal(cart: Cart): number {
  const cents = cart.reduce(
    (sum, line) => sum + Math.round(line.unitPrice * 100) * line.quantity,
    0,
  );
  return cents / 100;
}
