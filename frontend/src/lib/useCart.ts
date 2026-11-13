import { useCallback, useEffect, useState } from 'react';
import { addToCart, cartTotal, setQuantity, type Cart } from './cart';
import type { Product } from '../api/types';

const CART_KEY = 'orders.cart';

function readCart(): Cart {
  try {
    const raw = window.localStorage.getItem(CART_KEY);
    if (raw === null) {
      return [];
    }
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) ? (parsed as Cart) : [];
  } catch {
    return [];
  }
}

function writeCart(cart: Cart): void {
  try {
    window.localStorage.setItem(CART_KEY, JSON.stringify(cart));
  } catch {
    // Storage unavailable: the cart simply does not survive a reload.
  }
}

/** The cart, kept in localStorage so it survives a page reload until checkout. */
export function useCart(): {
  cart: Cart;
  total: number;
  add: (product: Product, quantity?: number) => void;
  setLine: (productId: string, quantity: number, product?: Product) => void;
  clear: () => void;
} {
  const [cart, setCart] = useState<Cart>(() => readCart());

  useEffect(() => {
    writeCart(cart);
  }, [cart]);

  const add = useCallback((product: Product, quantity = 1) => {
    setCart((current) => addToCart(current, product, quantity));
  }, []);

  const setLine = useCallback((productId: string, quantity: number, product?: Product) => {
    setCart((current) => setQuantity(current, productId, quantity, product));
  }, []);

  const clear = useCallback(() => {
    setCart([]);
  }, []);

  return { cart, total: cartTotal(cart), add, setLine, clear };
}
