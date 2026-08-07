import { Suspense } from "react";
import { LoginForm } from "./LoginForm";

export default function StoreLoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
